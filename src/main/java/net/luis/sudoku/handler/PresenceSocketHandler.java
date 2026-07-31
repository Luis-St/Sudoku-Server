package net.luis.sudoku.handler;

import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.auth.SessionService;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.presence.*;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The presence socket, {@code WS /ws/v1/presence}: a signed-in client holds one open for as long as it
 * is running, which is what makes it show as online to everyone else and what match requests are
 * delivered over.
 * <p>
 * Nothing is accepted from the client - every frame here is server-authored - so inbound messages are
 * dropped rather than parsed. That keeps the socket unable to carry anything a REST endpoint should be
 * authorizing instead.
 */
public class PresenceSocketHandler implements Consumer<WsConfig> {
	
	private static final Logger log = LoggerFactory.getLogger(PresenceSocketHandler.class);
	
	private final SessionService sessions;
	private final PresenceService presence;
	private final Clock clock;
	
	private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
	
	public PresenceSocketHandler(@NonNull SessionService sessions, @NonNull PresenceService presence, @NonNull Clock clock) {
		this.sessions = sessions;
		this.presence = presence;
		this.clock = clock;
	}
	
	@Override
	public void accept(@NonNull WsConfig ws) {
		ws.onConnect(this::onConnect);
		ws.onClose(this::onClose);
		ws.onError(context -> log.debug("Presence socket error", context.error()));
	}
	
	private void onConnect(@NonNull WsContext context) {
		try {
			// Same as the match socket: an upgrade carries no Authorization header, so the token may ride
			// a query parameter (spec 6.1).
			String token = context.queryParam(Authentication.TOKEN_QUERY_PARAM);
			if (token == null || token.isBlank()) {
				context.closeSession(4401, "UNAUTHORIZED");
				return;
			}
			Principal principal = this.sessions.authenticate(token, this.clock.instant());
			
			SocketConnection connection = new SocketConnection(context);
			this.registrations.put(context.sessionId(), new Registration(principal.userId(), connection));
			this.presence.register(principal.userId(), connection);
			
		} catch (ApiException e) {
			context.closeSession(4401, e.code().name());
		} catch (RuntimeException e) {
			log.warn("Failed to open a presence socket", e);
			context.closeSession(4500, "INTERNAL");
		}
	}
	
	private void onClose(@NonNull WsContext context) {
		Registration registration = this.registrations.remove(context.sessionId());
		if (registration != null) {
			this.presence.unregister(registration.userId, registration.connection);
		}
	}
	
	private record Registration(@NonNull UUID userId, @NonNull SocketConnection connection) {}
	
	private record SocketConnection(WsContext context) implements PresenceConnection {
		
		@Override
		public void send(@NonNull PresenceMessage message) {
			try {
				this.context.send(message);
			} catch (RuntimeException e) {
				log.debug("Failed to send on a closed presence socket", e);
			}
		}
		
		@Override
		public boolean isOpen() {
			return this.context.session.isOpen();
		}
	}
}
