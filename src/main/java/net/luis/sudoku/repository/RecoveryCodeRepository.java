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
 * Reads and writes {@code recovery_codes} (account recovery). Structurally the same access pattern as
 * {@link LinkCodeRepository}, but the code is redeemable without an existing session.
 */
public final class RecoveryCodeRepository {
	
	public void create(@NonNull SqlTransaction transaction, @NonNull String code, @NonNull UUID userId, @NonNull Instant expiresAt, @NonNull Instant now) throws SqlException {
		Schema.RecoveryCodeRow draft = new Schema.RecoveryCodeRow(code, userId, expiresAt, null, now);
		transaction.from(RECOVERY_CODES).insert(draft).execute();
	}
	
	/**
	 * Atomically consumes a recovery code, returning the user it was bound to.
	 *
	 * @return the target user id, or null if the code was unknown, expired or already used
	 */
	public @Nullable UUID consume(@NonNull SqlTransaction transaction, @NonNull String code, @NonNull Instant now) throws SqlException {
		List<Schema.RecoveryCodeRow> updated = transaction.from(RECOVERY_CODES).update()
			.set(RECOVERY_CONSUMED_AT, now)
			.where(Sql.equalTo(RECOVERY_CODE, code))
			.where(Sql.isNull(RECOVERY_CONSUMED_AT))
			.where(Sql.greaterThan(RECOVERY_EXPIRES_AT, now))
			.returning();
		return updated.isEmpty() ? null : updated.getFirst().userId();
	}
	
	/**
	 * Drops a user's outstanding codes: single live code per user (server-spec recovery, mirroring
	 * link codes).
	 */
	public void deleteUnusedForUser(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		transaction.from(RECOVERY_CODES).delete()
			.where(Sql.equalTo(RECOVERY_USER_ID, userId))
			.where(Sql.isNull(RECOVERY_CONSUMED_AT))
			.execute();
	}
	
	public int deleteExpired(@NonNull SqlTransaction transaction, @NonNull Instant now) throws SqlException {
		return transaction.from(RECOVERY_CODES).delete()
			.where(SqlCondition.anyOf(Sql.lessThanOrEqualTo(RECOVERY_EXPIRES_AT, now), Sql.isNull(RECOVERY_CONSUMED_AT).not()))
			.execute();
	}
}
