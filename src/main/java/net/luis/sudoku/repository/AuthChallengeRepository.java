package net.luis.sudoku.repository;

import net.luis.sudoku.db.schema.Schema;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code auth_challenges}.
 * <p>
 * Nonces are single-use and <em>deleted</em> on consumption rather than flagged (server-spec 12), so a
 * replayed nonce cannot be distinguished from one that never existed - which is exactly the intent.
 */
public final class AuthChallengeRepository {
	
	public void create(@NonNull SqlTransaction transaction, byte @NonNull [] nonce, byte @NonNull [] publicKey,
	                   @NonNull Instant expiresAt) throws SqlException {
		Schema.AuthChallengeRow draft = new Schema.AuthChallengeRow(nonce, publicKey, expiresAt);
		transaction.from(AUTH_CHALLENGES).insert(draft).execute();
	}
	
	/**
	 * Atomically consumes a nonce: deletes it and returns what it was bound to, or null if it was
	 * already used, expired, or never existed.
	 * <p>
	 * {@code DELETE ... RETURNING} is what makes this single-use under concurrency. A read followed by a
	 * separate delete would let two simultaneous verifications both succeed on one nonce.
	 *
	 * @return the public key the challenge was issued for, or null if the nonce was not consumable
	 */
	public byte @Nullable [] consume(@NonNull SqlTransaction transaction, byte @NonNull [] nonce, @NonNull Instant now) throws SqlException {
		List<Schema.AuthChallengeRow> deleted = transaction.from(AUTH_CHALLENGES).delete()
			.where(Sql.equalTo(CHALLENGE_NONCE, nonce))
			.where(Sql.greaterThan(CHALLENGE_EXPIRES_AT, now))
			.returning();
		return deleted.isEmpty() ? null : deleted.getFirst().publicKey();
	}
	
	/**
	 * Drops any outstanding challenges for a key, so requesting a new one invalidates the old.
	 */
	public void deleteForKey(@NonNull SqlTransaction transaction, byte @NonNull [] publicKey) throws SqlException {
		transaction.from(AUTH_CHALLENGES).delete().where(Sql.equalTo(CHALLENGE_PUBLIC_KEY, publicKey)).execute();
	}
	
	public int deleteExpired(@NonNull SqlTransaction transaction, @NonNull Instant now) throws SqlException {
		return transaction.from(AUTH_CHALLENGES).delete().where(Sql.lessThanOrEqualTo(CHALLENGE_EXPIRES_AT, now)).execute();
	}
}
