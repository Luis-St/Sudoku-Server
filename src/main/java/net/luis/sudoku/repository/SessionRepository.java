package net.luis.sudoku.repository;

import net.luis.sudoku.domain.Session;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code sessions}, which holds at most one row per device (server-spec 6.2).
 */
public final class SessionRepository {

	public @Nullable Session findByToken(@NonNull SqlTransaction transaction, @NonNull String token) throws SqlException {
		return transaction.from(SESSIONS).select().where(Sql.equalTo(SESSION_TOKEN, token)).fetchOneOrNull();
	}

	/**
	 * Finds the session a token was replaced by, if that token was the previous one for its device.
	 * <p>
	 * The only caller is authentication's failure path: it is what lets a stale token be answered
	 * {@code SESSION_SUPERSEDED} instead of the {@code UNAUTHORIZED} an unknown token gets, which are
	 * otherwise indistinguishable from the outside.
	 */
	public @Nullable Session findBySupersededToken(@NonNull SqlTransaction transaction, @NonNull String token) throws SqlException {
		return transaction.from(SESSIONS).select().where(Sql.equalTo(SESSION_SUPERSEDED_TOKEN, token)).fetchOneOrNull();
	}

	public @Nullable Session findByDevice(@NonNull SqlTransaction transaction, @NonNull UUID deviceId) throws SqlException {
		return transaction.from(SESSIONS).select().where(Sql.equalTo(SESSION_DEVICE_ID, deviceId)).fetchOneOrNull();
	}

	/**
	 * Every live session of a user, one per signed-in device.
	 */
	public @NonNull List<Session> findAllByUser(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		return transaction.from(SESSIONS).select().where(Sql.equalTo(SESSION_USER_ID, userId)).fetch();
	}

	/**
	 * Deletes any existing session <em>for the same device</em> and inserts the new one, which carries the
	 * replaced token so authentication can still recognise it.
	 * <p>
	 * Both statements are in the caller's transaction, so the unique constraint on {@code device_id} can
	 * never be violated by an interleaved login. Scoping this to the device rather than the user is the
	 * whole of the multi-device fix: another device of the same user is not touched.
	 *
	 * @return the previous session of that device, if there was one, so the caller can close its sockets
	 */
	public @Nullable Session replace(@NonNull SqlTransaction transaction, @NonNull Session session) throws SqlException {
		Session previous = this.findByDevice(transaction, session.deviceId());
		if (previous != null) {
			this.deleteByDevice(transaction, session.deviceId());
		}

		Session stored = previous == null ? session : session.superseding(previous, session.issuedAt());
		transaction.from(SESSIONS).insert(stored).execute();
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
