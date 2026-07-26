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
 * The match WebSocket, {@code WS /ws/v1/matches/{id}} (server-spec 10.2, 10.3).
 * <p>
 * This class does authentication, framing and validation only. Every accepted message is handed to the
 * match's own queue, so no game state is ever touched from a socket thread (spec 10.4).
 */
public class MatchSocketHandler implements Consumer<WsConfig> {
	
	private static final Logger log = LoggerFactory.getLogger(MatchSocketHandler.class);
	
	/** Spec 12: WebSocket frames are size-capped. A PLACE payload is tens of bytes. */
	private static final int MAX_FRAME_BYTES = 8 * 1024;
	
	/** Spec 12: the message rate per connection is limited. */
	private static final int MAX_MESSAGES_PER_WINDOW = 120;
	private static final long RATE_WINDOW_MS = 10_000;
	
	private final Authentication authentication;
	private final SessionService sessions;
	private final MatchService matches;
	private final Clock clock;
	
	private final Map<String, SocketState> states = new ConcurrentHashMap<>();
	
	public MatchSocketHandler(@NonNull Authentication authentication, @NonNull SessionService sessions,
	                          @NonNull MatchService matches, @NonNull RateLimiter rateLimiter, @NonNull Clock clock) {
		this.authentication = authentication;
		this.sessions = sessions;
		this.matches = matches;
		this.clock = clock;
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
			LiveMatch live = this.matches.liveFor(match);
			SocketConnection connection = new SocketConnection(context, principal);
			
			this.states.put(context.sessionId(), new SocketState(matchId, principal.userId(), live, connection));
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
	
	private void onClose(@NonNull WsContext context) {
		SocketState state = this.states.remove(context.sessionId());
		if (state == null) {
			return;
		}
		LiveMatch live = state.live;
		// A bare socket close is a network failure, not a quit: the ordinary grace window applies
		// (spec 10.4). An explicit RESIGN takes the other path.
		live.submit(() -> live.onDisconnect(state.userId, false));
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
		
		private SocketState(@NonNull UUID matchId, @NonNull UUID userId, @NonNull LiveMatch live,
		                    @NonNull SocketConnection connection) {
			this.matchId = matchId;
			this.userId = userId;
			this.live = live;
			this.connection = connection;
		}
		
		/**
		 * @return true if this sequence number is new, false if it is a replay
		 */
		private boolean accept(long seq) {
			return this.highestSeq.updateAndGet(current -> Math.max(current, seq)) == seq
				&& this.highestSeq.get() == seq;
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
		private record SocketConnection(WsContext context, Principal principal) implements Connection {
			
			private SocketConnection(@NonNull WsContext context, @NonNull Principal principal) {
				this.context = context;
				this.principal = principal;
			}
			
			@Override
			public @NonNull UUID userId() {
				return this.principal.userId();
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
