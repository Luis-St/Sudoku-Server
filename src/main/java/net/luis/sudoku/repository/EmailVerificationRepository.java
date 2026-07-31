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
 * Reads and writes {@code email_verifications} (account recovery: proving ownership of an email).
 */
public final class EmailVerificationRepository {
	
	public void create(@NonNull SqlTransaction transaction, @NonNull String code, @NonNull UUID userId, @NonNull String email, @NonNull Instant expiresAt, @NonNull Instant now) throws SqlException {
		Schema.EmailVerificationRow draft = new Schema.EmailVerificationRow(code, userId, email, expiresAt, null, now);
		transaction.from(EMAIL_VERIFICATIONS).insert(draft).execute();
	}
	
	/**
	 * Atomically consumes a verification code, bound to the user it was minted for.
	 *
	 * @return the consumed row, or null if the code was unknown, expired, already used, or not this
	 *   user's code
	 */
	public Schema.@Nullable EmailVerificationRow consume(@NonNull SqlTransaction transaction, @NonNull String code, @NonNull UUID userId, @NonNull Instant now) throws SqlException {
		List<Schema.EmailVerificationRow> updated = transaction.from(EMAIL_VERIFICATIONS).update()
			.set(EMAIL_VERIFICATION_CONSUMED_AT, now)
			.where(Sql.equalTo(EMAIL_VERIFICATION_CODE, code))
			.where(Sql.equalTo(EMAIL_VERIFICATION_USER_ID, userId))
			.where(Sql.isNull(EMAIL_VERIFICATION_CONSUMED_AT))
			.where(Sql.greaterThan(EMAIL_VERIFICATION_EXPIRES_AT, now))
			.returning();
		return updated.isEmpty() ? null : updated.getFirst();
	}
	
	/**
	 * Drops a user's outstanding codes, so requesting a new one supersedes any earlier one.
	 */
	public void deleteUnusedForUser(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		transaction.from(EMAIL_VERIFICATIONS).delete()
			.where(Sql.equalTo(EMAIL_VERIFICATION_USER_ID, userId))
			.where(Sql.isNull(EMAIL_VERIFICATION_CONSUMED_AT))
			.execute();
	}
	
	public int deleteExpired(@NonNull SqlTransaction transaction, @NonNull Instant now) throws SqlException {
		return transaction.from(EMAIL_VERIFICATIONS).delete()
			.where(SqlCondition.anyOf(Sql.lessThanOrEqualTo(EMAIL_VERIFICATION_EXPIRES_AT, now), Sql.isNull(EMAIL_VERIFICATION_CONSUMED_AT).not()))
			.execute();
	}
}
