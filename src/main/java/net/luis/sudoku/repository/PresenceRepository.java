package net.luis.sudoku.repository;

import net.luis.sudoku.db.schema.Schema;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.SqlConnectionSource;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.query.crud.SqlInsertQuery;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.*;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code presence}, the last heartbeat per user (feature-spec 9.7).
 * <p>
 * Nothing here decides who is online: every read takes the threshold instant from the caller, so the
 * "younger than the TTL" rule lives in one place ({@code PresenceService}) against one clock, rather than
 * being re-derived by each query.
 */
public final class PresenceRepository {
	
	/**
	 * Records that this user's client is running right now.
	 * <p>
	 * The row's only non-key column always moves to the given value, which is exactly what the query
	 * builder's generic upsert expresses.
	 */
	public void touch(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull Instant now) throws SqlException {
		Schema.PresenceRow draft = new Schema.PresenceRow(userId, now);
		SqlInsertQuery.upsert(PRESENCE, transaction.getDialect(), SqlConnectionSource.fixed(transaction.getConnection()),
			transaction.getQueryTimeout(), resultSet -> null, List.of(draft), PRESENCE_USER_ID).execute();
	}
	
	/**
	 * Drops the user's heartbeat, making them offline immediately rather than once it goes stale.
	 * <p>
	 * This is what sign-out and backgrounding call. Without it a player who closed the app would keep
	 * showing online for the rest of the TTL, and the friends list would offer to invite them into a match
	 * nothing is going to answer.
	 */
	public void clear(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		transaction.from(PRESENCE).delete().where(Sql.equalTo(PRESENCE_USER_ID, userId)).execute();
	}
	
	/**
	 * @param since the oldest heartbeat that still counts as online
	 * @return every user whose last heartbeat is at or after {@code since}
	 */
	public @NonNull Set<UUID> onlineSince(@NonNull SqlTransaction transaction, @NonNull Instant since) throws SqlException {
		return Set.copyOf(transaction.from(PRESENCE).select(PRESENCE_USER_ID)
			.where(Sql.greaterThanOrEqualTo(PRESENCE_LAST_SEEN_AT, since))
			.fetch());
	}
	
	public boolean isOnlineSince(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull Instant since) throws SqlException {
		Instant lastSeen = transaction.from(PRESENCE).select(PRESENCE_LAST_SEEN_AT)
			.where(Sql.equalTo(PRESENCE_USER_ID, userId))
			.fetchOneOrNull();
		return lastSeen != null && !lastSeen.isBefore(since);
	}
	
	/**
	 * Removes heartbeats old enough that they will never be online again, so the table stays the size of
	 * the recently-active population rather than of everyone who ever signed in.
	 *
	 * @return how many rows were removed
	 */
	public int deleteBefore(@NonNull SqlTransaction transaction, @NonNull Instant cutoff) throws SqlException {
		return transaction.from(PRESENCE).delete().where(Sql.lessThan(PRESENCE_LAST_SEEN_AT, cutoff)).execute();
	}
}
