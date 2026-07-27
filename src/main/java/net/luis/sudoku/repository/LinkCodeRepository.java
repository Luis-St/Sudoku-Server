package net.luis.sudoku.repository;

import net.luis.sudoku.db.schema.Schema;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.condition.SqlCondition;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code link_codes} (server-spec 6.4).
 */
public final class LinkCodeRepository {
	
	public void create(@NonNull SqlTransaction transaction, @NonNull String code, @NonNull UUID userId, @NonNull Instant expiresAt, @NonNull Instant now) throws SqlException {
		Schema.LinkCodeRow draft = new Schema.LinkCodeRow(code, userId, expiresAt, null, now);
		transaction.from(LINK_CODES).insert(draft).execute();
	}
	
	/**
	 * Atomically consumes a link code, returning the user it was bound to.
	 * <p>
	 * A conditional {@code UPDATE ... RETURNING} rather than a read followed by a write: two devices
	 * submitting the same code at once must not both link.
	 *
	 * @return the target user id, or null if the code was unknown, expired or already used
	 */
	public @Nullable UUID consume(@NonNull SqlTransaction transaction, @NonNull String code, @NonNull Instant now) throws SqlException {
		List<Schema.LinkCodeRow> updated = transaction.from(LINK_CODES).update()
			.set(LINK_CONSUMED_AT, now)
			.where(Sql.equalTo(LINK_CODE, code))
			.where(Sql.isNull(LINK_CONSUMED_AT))
			.where(Sql.greaterThan(LINK_EXPIRES_AT, now))
			.returning();
		return updated.isEmpty() ? null : updated.getFirst().userId();
	}
	
	/**
	 * Drops a user's outstanding codes, so requesting a new one supersedes any earlier one.
	 */
	public void deleteUnusedForUser(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		transaction.from(LINK_CODES).delete()
			.where(Sql.equalTo(LINK_USER_ID, userId))
			.where(Sql.isNull(LINK_CONSUMED_AT))
			.execute();
	}
	
	public int deleteExpired(@NonNull SqlTransaction transaction, @NonNull Instant now) throws SqlException {
		return transaction.from(LINK_CODES).delete()
			.where(SqlCondition.anyOf(Sql.lessThanOrEqualTo(LINK_EXPIRES_AT, now), Sql.isNull(LINK_CONSUMED_AT).not()))
			.execute();
	}
}
