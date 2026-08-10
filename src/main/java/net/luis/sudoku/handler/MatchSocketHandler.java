package net.luis.sudoku.handler;

import io.javalin.websocket.*;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.auth.SessionService;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.match.*;
import net.luis.sudoku.security.RateLimiter;
import net.luis.sudoku.version.GenVersion;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The match WebSocket, {@code WS /ws/v1/matches/{id}} and {@code WS /ws/v2/matches/{id}}
 * (server-spec 10.2, 10.3).
 * <p>
 * This class does authentication, framing and validation only. Every accepted message is handed to the
 * match's own queue, so no game state is ever touched from a socket thread (spec 10.4).
 * <p>
 * One instance is registered per version, each stamping its {@link #apiVersion} onto the connections it
 * opens. That is the only difference between the two paths: a v1 socket has its {@code MATCH_STATE}
 * {@code puzzleKey} reduced to the six-tier integer with no givens, a v2 socket gets the real fifteen-tier
 * index and the givens. Both may be attached to the same match at the same time.
 */
public class MatchSocketHandler implements Consumer<WsConfig> {
	
	private static final Logger log = LoggerFactory.getLogger(MatchSocketHandler.class);
	/** Spec 12: the message rate per connection is limited. */
	private static final int MAX_MESSAGES_PER_WINDOW = 120;
	private static final long RATE_WINDOW_MS = 10_000;
	/** Spec 12: WebSocket frames are size-capped. A PLACE payload is tens of bytes. */
	public static final int MAX_FRAME_BYTES = 8 * 1024;
	/**
	 * How long a match socket may be silent before Jetty closes it (set in {@code Application}).
	 * <p>
	 * A match is legitimately silent for minutes at a time - co-op and race send nothing while the players
	 * are thinking - so this is not a liveness signal on its own. What makes it one is the client's ping
	 * every {@link #CLIENT_PING_SECONDS}: this is that interval with room for several to go missing on a
	 * mobile connection, while still expiring inside the reconnect grace window so a peer that really is
	 * gone is noticed rather than held open until the match ends.
	 */
	public static final long SOCKET_IDLE_TIMEOUT_SECONDS = 90;
	/** The ping interval the clients are configured with; documented here because the timeout above derives from it. */
	public static final long CLIENT_PING_SECONDS = 20;
	private final Authentication authentication;
	private final SessionService sessions;
	private final MatchService matches;
	private final Clock clock;
	/** The version of the path this instance is registered on; stamped onto every connection it opens. */
	private final int apiVersion;
	
	private final Map<String, SocketState> states = new ConcurrentHashMap<>();
	
	public MatchSocketHandler(@NonNull Authentication authentication, @NonNull SessionService sessions, @NonNull MatchService matches, @NonNull RateLimiter rateLimiter, @NonNull Clock clock) {
		this(authentication, sessions, matches, rateLimiter, clock, 1);
	}
	
	/**
	 * @param authentication The authenticator
	 * @param sessions The session store a socket token is resolved against
	 * @param matches The match service
	 * @param rateLimiter The shared rate limiter
	 * @param clock The clock
	 * @param apiVersion The version of the path this instance is registered on
	 */
	public MatchSocketHandler(@NonNull Authentication authentication, @NonNull SessionService sessions, @NonNull MatchService matches, @NonNull RateLimiter rateLimiter, @NonNull Clock clock, int apiVersion) {
		this.authentication = authentication;
		this.sessions = sessions;
		this.matches = matches;
		this.clock = clock;
		this.apiVersion = apiVersion;
	}
	
	@Override
	public void accept(@NonNull WsConfig ws) {
		ws.onConnect(this::onConnect);
		ws.onMessage(this::onMessage);
		ws.onClose(this::onClose);
		ws.onError(context -> log.debug("Match socket error", context.error()));
	}
	
	private void onConnect(@NonNull WsContext context) {
		try {
			// A WebSocket upgrade cannot carry an Authorization header from a browser, so the token may
			// also arrive as a query parameter (spec 6.1).
			String token = context.queryParam(Authentication.TOKEN_QUERY_PARAM);
			if (token == null || token.isBlank()) {
				context.closeSession(4401, "UNAUTHORIZED");
				return;
			}
			Principal principal = this.sessions.authenticate(token, this.clock.instant());
			
			UUID matchId;
			try {
				matchId = UUID.fromString(context.pathParam("id"));
			} catch (IllegalArgumentException e) {
				context.closeSession(4400, "BAD_MATCH_ID");
				return;
			}
			
			if (!this.matches.isParticipant(matchId, principal.userId())) {
				context.closeSession(4403, "FORBIDDEN");
				return;
			}
			
			Match match = this.matches.get(matchId);
			
			// A match that is already over must never be rebuilt. `liveFor` reconstructs a LiveMatch from the
			// row when the registry has lost it, and for a finished row that produced a *zombie*: a fresh
			// board, an empty grid and no memory of having ended, which it then served to the returning
			// player as an ordinary snapshot. The player who closed the app during a co-op game came back to
			// a match that had been abandoned while they were gone and was shown a blank board instead of
			// being told, so nothing ever sent them back to the home screen.
			//
			// The outcome is on the row, so it can be told properly: the same MATCH_ENDED every other player
			// received when it happened, then the socket closes. The client shows the match-over dialog and
			// leaves from there.
			if (match.state().isTerminal()) {
				log.info("Match {}: user {} connected to a match that already ended ({})", matchId, principal.userId(), match.endReason());
				Map<String, Object> payload = new java.util.HashMap<>();
				payload.put("winnerId", match.winnerId() == null ? null : match.winnerId().toString());
				payload.put("reason", match.endReason() == null ? EndReason.DISCONNECTED.name() : match.endReason().name());
				context.send(new MessageEnvelope(MessageType.MATCH_ENDED.name(), 0, System.currentTimeMillis(), payload));
				context.closeSession(4000, "MATCH_OVER");
				return;
			}
			
			LiveMatch live = this.matches.liveFor(match);
			SocketConnection connection = new SocketConnection(context, principal, this.apiVersion);
			
			this.states.put(context.sessionId(), new SocketState(matchId, principal.userId(), live, connection));
			log.info("Match {}: user {} opened a socket", matchId, principal.userId());
			live.submit(() -> live.onConnect(connection));
			
		} catch (ApiException e) {
			context.closeSession(4401, e.code().name());
		} catch (RuntimeException e) {
			log.warn("Failed to open a match socket", e);
			context.closeSession(4500, "INTERNAL");
		}
	}
	
	private void onMessage(@NonNull WsMessageContext context) {
		SocketState state = this.states.get(context.sessionId());
		if (state == null) {
			return;
		}
		if (context.message().length() > MAX_FRAME_BYTES) {
			context.closeSession(4413, "FRAME_TOO_LARGE");
			return;
		}
		if (!state.allowMessage(this.clock.millis())) {
			context.closeSession(4429, "RATE_LIMITED");
			return;
		}
		
		MessageEnvelope envelope;
		try {
			envelope = context.messageAsClass(MessageEnvelope.class);
		} catch (RuntimeException e) {
			state.connection.send(MessageEnvelope.of(MessageType.ERROR,
				Map.of("error", "BAD_REQUEST", "message", "Frame is not a valid envelope")));
			return;
		}
		
		MessageType type;
		try {
			type = MessageType.of(envelope.type());
		} catch (ApiException e) {
			state.connection.send(MessageEnvelope.of(MessageType.ERROR,
				Map.of("error", "BAD_REQUEST", "message", e.getMessage())));
			return;
		}
		if (!type.acceptsFromClient()) {
			// Stops a client injecting a server-authored type such as MATCH_ENDED.
			state.connection.send(MessageEnvelope.of(MessageType.ERROR,
				Map.of("error", "FORBIDDEN", "message", type + " is server-authored")));
			return;
		}
		
		// Idempotent replay after a reconnect: anything at or below the highest seq already processed
		// is a duplicate and must not be applied twice (spec 10.2).
		if (envelope.seq() > 0 && !state.accept(envelope.seq())) {
			state.connection.send(MessageEnvelope.of(MessageType.ACK, Map.of("seq", state.highestSeq.get())));
			return;
		}
		
		if (type == MessageType.HELLO) {
			this.handleHello(state, envelope);
			return;
		}
		
		Map<String, Object> payload = envelope.payloadOrEmpty();
		LiveMatch live = state.live;
		live.submit(() -> live.onMessage(state.userId, type, payload));
		state.connection.send(MessageEnvelope.of(MessageType.ACK, Map.of("seq", state.highestSeq.get())));
	}
	
	/**
	 * The compatibility gate: a client whose shared-core generates different puzzles must not play, or
	 * the two sides would disagree about what the board is (spec 13, {@code GEN_VERSION_MISMATCH}).
	 */
	private void handleHello(@NonNull SocketState state, @NonNull MessageEnvelope envelope) {
		Object claimed = envelope.payloadOrEmpty().get("clientGenVersion");
		Integer version = claimed instanceof Number number ? number.intValue() : null;
		
		if (version == null || version != GenVersion.CURRENT) {
			state.connection.send(MessageEnvelope.of(MessageType.ERROR, Map.of(
				"error", "GEN_VERSION_MISMATCH",
				"message", "This server generates genVersion " + GenVersion.CURRENT
			)));
			state.connection.close("GEN_VERSION_MISMATCH");
			return;
		}
		state.connection.send(MessageEnvelope.of(MessageType.ACK, Map.of("seq", state.highestSeq.get())));
	}
	
	private void onClose(@NonNull WsCloseContext context) {
		SocketState state = this.states.remove(context.sessionId());
		if (state == null) {
			return;
		}
		// Logged at info because this is the event the players see as "disconnected", and it was previously
		// silent on both sides: an idle-timeout close and a phone leaving the network were indistinguishable
		// from the logs, which is to say invisible.
		log.info("Match {}: user {} socket closed ({} {})", state.matchId, state.userId, context.status(), context.reason());
		LiveMatch live = state.live;
		// A bare socket close is a network failure, not a quit: the ordinary grace window applies
		// (spec 10.4). An explicit RESIGN takes the other path.
		//
		// The connection is passed so a *stale* close cannot end a live match. Jetty may report a dropped
		// socket well after the client has already reconnected on a new one, and without this the late close
		// tears down the replacement: the returning player is dropped again, their reconnect count rises
		// towards the cap, and the other player is shown a disconnect for somebody who is sitting there.
		live.submit(() -> live.onDisconnect(state.userId, state.connection, false));
	}
	
	/**
	 * Per-socket bookkeeping: sequence tracking and the message-rate window.
	 */
	private static final class SocketState {
		
		private final UUID matchId;
		private final UUID userId;
		private final LiveMatch live;
		private final SocketConnection connection;
		private final AtomicLong highestSeq = new AtomicLong();
		
		private long windowStartedAtMs;
		private int messagesInWindow;
		
		private SocketState(@NonNull UUID matchId, @NonNull UUID userId, @NonNull LiveMatch live, @NonNull SocketConnection connection) {
			this.matchId = matchId;
			this.userId = userId;
			this.live = live;
			this.connection = connection;
		}
		
		/**
		 * @return true if this sequence number is new, false if it is a replay
		 */
		private boolean accept(long seq) {
			return this.highestSeq.updateAndGet(current -> Math.max(current, seq)) == seq && this.highestSeq.get() == seq;
		}
		
		private synchronized boolean allowMessage(long nowMs) {
			if (nowMs - this.windowStartedAtMs > RATE_WINDOW_MS) {
				this.windowStartedAtMs = nowMs;
				this.messagesInWindow = 0;
			}
			return ++this.messagesInWindow <= MAX_MESSAGES_PER_WINDOW;
		}
	}
	
	/**
	 * Adapts a Javalin {@link WsContext} to the transport-free {@link Connection} the match logic uses.
	 */
	private record SocketConnection(WsContext context, Principal principal, int version) implements Connection {
		
		private SocketConnection(@NonNull WsContext context, @NonNull Principal principal, int version) {
			this.context = context;
			this.principal = principal;
			this.version = version;
		}
		
		@Override
		public int apiVersion() {
			return this.version;
		}
		
		@Override
		public @NonNull UUID userId() {
			return this.principal.userId();
		}
		
		@Override
		public @NonNull UUID deviceId() {
			return this.principal.deviceId();
		}

		@Override
		public @NonNull String displayName() {
			return this.principal.user().displayName();
		}
		
		@Override
		public void send(@NonNull MessageEnvelope message) {
			try {
				this.context.send(message);
			} catch (RuntimeException e) {
				log.debug("Failed to send on a closed match socket", e);
			}
		}
		
		@Override
		public void close(@NonNull String reason) {
			try {
				this.context.closeSession(4000, reason);
			} catch (RuntimeException e) {
				log.debug("Failed to close a match socket", e);
			}
		}
		
		@Override
		public boolean isOpen() {
			return this.context.session.isOpen();
		}
	}
}
