package net.luis.sudoku.match;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Holds every running match and routes work to it (server-spec 10.4).
 * <p>
 * Each match is an in-memory object owned by a <strong>single-threaded executor</strong> - one queue per
 * match. Every mutation (entries, clock ticks, joins, disconnects) is submitted to that queue, so
 * ordering within a match is total and the match logic itself needs no locking at all. Javalin's request
 * and WebSocket threads only ever enqueue.
 * <p>
 * This is also why the server does not scale horizontally: live state is owned by one JVM. Running two
 * containers against one database would split matches unpredictably (spec 5.1).
 */
public final class MatchRegistry implements AutoCloseable {
	
	private static final Logger log = LoggerFactory.getLogger(MatchRegistry.class);
	
	private final Map<UUID, LiveMatch> matches = new ConcurrentHashMap<>();
	
	/**
	 * Registers a live match, replacing any existing one for that id.
	 */
	public void register(@NonNull LiveMatch match) {
		LiveMatch previous = this.matches.put(match.id(), match);
		if (previous != null) {
			previous.shutdown();
		}
		log.info("Match {} registered ({})", match.id(), match.mode());
	}
	
	public @Nullable LiveMatch find(@NonNull UUID matchId) {
		return this.matches.get(matchId);
	}
	
	/**
	 * Removes and shuts down a match once it has ended.
	 */
	public void remove(@NonNull UUID matchId) {
		LiveMatch removed = this.matches.remove(matchId);
		if (removed != null) {
			removed.shutdown();
			log.info("Match {} removed from the registry", matchId);
		}
	}
	
	/**
	 * @return how many matches are live, which {@code /health} reports
	 */
	public int activeCount() {
		return this.matches.size();
	}
	
	/**
	 * @return how many distinct players are currently connected to a live match, which the puzzle queue
	 *   sizes itself against
	 */
	public int connectedPlayerCount() {
		return this.matches.values().stream()
			.flatMap(match -> match.connectedUserIds().stream())
			.collect(Collectors.toSet())
			.size();
	}
	
	/**
	 * Closes every connection belonging to a user, across all matches.
	 * <p>
	 * This is what makes {@code SESSION_SUPERSEDED} (spec 6.2) and a kick (spec 7.2) actually take
	 * effect on a live socket rather than only in the database.
	 *
	 * @param deviceId the one device to close, or null for all of them - a kick revokes the whole
	 *   account, while a superseded session belongs to the single device that re-authenticated
	 */
	public void closeSocketsFor(@NonNull UUID userId, @Nullable UUID deviceId, @NonNull String reason) {
		for (LiveMatch match : this.matches.values()) {
			match.disconnectUser(userId, deviceId, reason);
		}
	}
	
	public @NonNull Set<UUID> liveMatchIds() {
		return Set.copyOf(this.matches.keySet());
	}
	
	@Override
	public void close() {
		for (LiveMatch match : this.matches.values()) {
			// Spec 14: close match sockets with SERVER_SHUTDOWN on the way down.
			match.broadcastShutdown();
			match.shutdown();
		}
		this.matches.clear();
	}
}
