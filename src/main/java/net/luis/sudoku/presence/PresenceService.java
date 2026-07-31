package net.luis.sudoku.presence;

import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is currently connected, and the only way to push something at them (feature-spec 9.7's online
 * status and player-to-player match requests).
 * <p>
 * In-memory on purpose: "online" means "has a socket open to <em>this</em> process right now", which is
 * exactly what the connection map already knows. Persisting it would only create rows that outlive the
 * truth - a killed process would leave everyone online forever.
 * <p>
 * One user may hold several connections (phone and tablet, or a reconnect racing its own close), so the
 * map is user to a set of connections and a user is online while any of them remains.
 */
public final class PresenceService {
	
	private final Map<UUID, Set<PresenceConnection>> connections = new ConcurrentHashMap<>();
	
	/**
	 * Registers a freshly opened connection and tells everyone the online set grew.
	 */
	public void register(@NonNull UUID userId, @NonNull PresenceConnection connection) {
		this.connections.computeIfAbsent(userId, _ -> ConcurrentHashMap.newKeySet()).add(connection);
		connection.send(this.onlineMessage());
		this.broadcastOnline();
	}
	
	/**
	 * Drops a closed connection, and the user with it once it was their last one.
	 */
	public void unregister(@NonNull UUID userId, @NonNull PresenceConnection connection) {
		this.connections.computeIfPresent(userId, (_, open) -> {
			open.remove(connection);
			return open.isEmpty() ? null : open;
		});
		this.broadcastOnline();
	}
	
	public boolean isOnline(@NonNull UUID userId) {
		return this.connections.containsKey(userId);
	}
	
	public @NonNull Set<UUID> onlineUsers() {
		return Set.copyOf(this.connections.keySet());
	}
	
	/**
	 * @return true if the message reached at least one open connection, false if the user turned out to
	 *   have none - a race the caller must handle, since online-ness can lapse between any check and
	 *   this send
	 */
	public boolean send(@NonNull UUID userId, @NonNull PresenceMessage message) {
		Set<PresenceConnection> open = this.connections.get(userId);
		if (open == null) {
			return false;
		}
		
		boolean delivered = false;
		for (PresenceConnection connection : open) {
			if (connection.isOpen()) {
				connection.send(message);
				delivered = true;
			}
		}
		return delivered;
	}
	
	private void broadcastOnline() {
		PresenceMessage message = this.onlineMessage();
		this.connections.values().forEach(open -> open.forEach(connection -> {
			if (connection.isOpen()) {
				connection.send(message);
			}
		}));
	}
	
	private @NonNull PresenceMessage onlineMessage() {
		List<String> ids = this.connections.keySet().stream().map(UUID::toString).toList();
		return PresenceMessage.of(PresenceMessage.Type.ONLINE, Map.of("userIds", ids));
	}
}
