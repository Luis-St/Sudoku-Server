package net.luis.sudoku.presence;

import net.luis.sudoku.config.PresenceConfig;
import net.luis.sudoku.db.Database;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.repository.MatchRequestRepository;
import net.luis.sudoku.repository.PresenceRepository;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who is online, and the match requests waiting for them (feature-spec 9.7).
 * <p>
 * Presence is a heartbeat, not a connection: a client says "I am here" every ten seconds and a player
 * counts as online while their last heartbeat is younger than {@link PresenceConfig#onlineTtlSeconds}.
 * <p>
 * <strong>This replaced a WebSocket, deliberately.</strong> Holding a socket open made "online" mean "the
 * server still believes a TCP connection is alive", which on mobile is regularly false in both
 * directions: a client that lost coverage or was frozen by Doze sends no close frame, so it stayed lit
 * until a timeout noticed, while the client itself could not tell a live-but-silent socket from a dead
 * one. A timestamp cannot get stuck: nobody has to notice a client is gone for it to go offline. It is
 * also the only version of this that survives more than one server process, since the shared database is
 * the state rather than each process's own connection map.
 * <p>
 * The cost, stated plainly: a status change takes up to a heartbeat interval to show, and up to the TTL
 * to lapse. For a dot on a friends list that is the right trade; for anything needing instant delivery it
 * would not be.
 */
public final class PresenceService {

	private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

	/**
	 * How long a heartbeat that can never be online again is kept before housekeeping drops it. Generous
	 * on purpose - the row is one primary key and one timestamp, and pruning it early buys nothing.
	 */
	private static final Duration STALE_RETENTION = Duration.ofHours(1);

	private final Database database;
	private final PresenceRepository presence;
	private final MatchRequestRepository requests;
	private final PresenceConfig config;
	private final Clock clock;

	public PresenceService(
		@NonNull Database database, @NonNull PresenceRepository presence, @NonNull MatchRequestRepository requests, @NonNull PresenceConfig config, @NonNull Clock clock
	) {
		this.database = database;
		this.presence = presence;
		this.requests = requests;
		this.config = config;
		this.clock = clock;
	}

	/**
	 * Records that this player's client is running, and hands back whatever is waiting for them.
	 * <p>
	 * Both halves are one call because the client has to make it every ten seconds anyway: giving match
	 * requests their own endpoint would double the request rate of every signed-in client to learn the same
	 * thing.
	 * <p>
	 * Requests are <strong>not</strong> consumed by being read. A client that is killed between receiving
	 * one and showing it would otherwise lose it silently; instead the row lives until the player dismisses
	 * it ({@link #dismissRequest}) or it expires, and a client that sees the same request twice is expected
	 * to recognise it by {@link PendingMatchRequest#id()}.
	 *
	 * @return the requests this player has pending, oldest first
	 */
	public @NonNull List<PendingMatchRequest> heartbeat(@NonNull UUID userId) {
		Instant now = this.clock.instant();
		return this.database.transaction(transaction -> {
			this.presence.touch(transaction, userId, now);
			return this.requests.findPending(transaction, userId, now);
		});
	}

	/**
	 * Takes this player offline now, rather than when their last heartbeat goes stale.
	 * <p>
	 * Called on sign-out and when the app stops heartbeating on purpose. Best-effort by nature: a client
	 * that is killed outright never gets to call it, which is exactly why the TTL exists as well.
	 */
	public void goOffline(@NonNull UUID userId) {
		this.database.execute(transaction -> this.presence.clear(transaction, userId));
	}

	public @NonNull Set<UUID> onlineUsers() {
		return this.database.read(transaction -> this.presence.onlineSince(transaction, this.onlineSince()));
	}

	public boolean isOnline(@NonNull UUID userId) {
		return this.database.read(transaction -> this.presence.isOnlineSince(transaction, userId, this.onlineSince()));
	}

	/**
	 * Stores a request for {@code target} to join {@code match}.
	 * <p>
	 * The online check and the write are one transaction, so a player who goes offline between the two
	 * cannot end up with a request they will never be asked about - and re-asking replaces the earlier
	 * request instead of leaving the target with two banners for one match.
	 *
	 * @throws ApiException {@link ErrorCode#PLAYER_OFFLINE} if the target has no fresh heartbeat
	 */
	public void requestMatch(@NonNull UUID targetUserId, @NonNull Match match, @NonNull UUID fromUserId) {
		Instant now = this.clock.instant();
		Instant expiresAt = now.plusSeconds(this.config.matchRequestTtlSeconds());

		this.database.execute(transaction -> {
			if (!this.presence.isOnlineSince(transaction, targetUserId, now.minusSeconds(this.config.onlineTtlSeconds()))) {
				throw new ApiException(ErrorCode.PLAYER_OFFLINE, "That player is not online");
			}
			this.requests.deleteFor(transaction, targetUserId, match.id());
			this.requests.create(transaction, targetUserId, match.id(), fromUserId, expiresAt, now);
		});
	}

	/**
	 * Removes a request the invited player has answered - by accepting it or by declining.
	 * <p>
	 * Only the player it was addressed to may do this, and an id that is already gone is not an error: a
	 * client retrying a dismissal it is not sure landed must not be handed a failure for having succeeded.
	 *
	 * @throws ApiException {@link ErrorCode#FORBIDDEN} if the request was addressed to somebody else
	 */
	public void dismissRequest(@NonNull UUID actorId, @NonNull UUID requestId) {
		this.database.execute(transaction -> {
			var row = this.requests.find(transaction, requestId);
			if (row == null) {
				return;
			}
			if (!row.targetUserId().equals(actorId)) {
				throw ApiException.forbidden("Only the invited player may dismiss a match request");
			}
			this.requests.delete(transaction, requestId);
		});
	}

	/**
	 * Drops expired requests and long-stale heartbeats. Called from the housekeeping pass; neither is
	 * visible to a client before this runs, so it is purely about table size.
	 */
	public void sweep() {
		Instant now = this.clock.instant();
		this.database.execute(transaction -> {
			int requests = this.requests.deleteExpired(transaction, now);
			int heartbeats = this.presence.deleteBefore(transaction, now.minus(STALE_RETENTION));
			if (requests > 0 || heartbeats > 0) {
				log.debug("Presence sweep dropped {} expired match requests and {} stale heartbeats", requests, heartbeats);
			}
		});
	}

	/** The oldest heartbeat that still counts as online. */
	private @NonNull Instant onlineSince() {
		return this.clock.instant().minusSeconds(this.config.onlineTtlSeconds());
	}
}
