package net.luis.sudoku.repository;

import net.luis.sudoku.domain.Session;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code sessions}, which holds at most one row per user (server-spec 6.2).
 */
public final class SessionRepository {
	
	public @Nullable Session findByToken(@NonNull SqlTransaction transaction, @NonNull String token) throws SqlException {
		return transaction.from(SESSIONS).select().where(Sql.equalTo(SESSION_TOKEN, token)).fetchOneOrNull();
	}
	
	public @Nullable Session findByUser(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		return transaction.from(SESSIONS).select().where(Sql.equalTo(SESSION_USER_ID, userId)).fetchOneOrNull();
	}
	
	/**
	 * Deletes any existing session for the user and inserts the new one.
	 * <p>
	 * Both statements are in the caller's transaction, so the unique constraint on {@code user_id} can
	 * never be violated by an interleaved login.
	 *
	 * @return the previous session, if there was one, so the caller can close its sockets
	 */
	public @Nullable Session replace(@NonNull SqlTransaction transaction, @NonNull Session session) throws SqlException {
		Session previous = this.findByUser(transaction, session.userId());
		if (previous != null) {
			this.deleteByUser(transaction, session.userId());
		}
		
		transaction.from(SESSIONS).insert(session).execute();
		return previous;
	}
	
	public void deleteByUser(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		transaction.from(SESSIONS).delete().where(Sql.equalTo(SESSION_USER_ID, userId)).execute();
	}
	
	public void deleteByToken(@NonNull SqlTransaction transaction, @NonNull String token) throws SqlException {
		transaction.from(SESSIONS).delete().where(Sql.equalTo(SESSION_TOKEN, token)).execute();
	}
	
	public void deleteByDevice(@NonNull SqlTransaction transaction, @NonNull UUID deviceId) throws SqlException {
		transaction.from(SESSIONS).delete().where(Sql.equalTo(SESSION_DEVICE_ID, deviceId)).execute();
	}
	
	public int deleteExpired(@NonNull SqlTransaction transaction, @NonNull Instant now) throws SqlException {
		return transaction.from(SESSIONS).delete().where(Sql.lessThanOrEqualTo(SESSION_EXPIRES_AT, now)).execute();
	}
}
