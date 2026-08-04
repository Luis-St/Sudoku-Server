package net.luis.sudoku.repository;

import net.luis.sudoku.domain.User;
import net.luis.sudoku.permission.Role;
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
 * Reads and writes {@code users}.
 */
public final class UserRepository {
	
	public @Nullable User find(@NonNull SqlTransaction transaction, @NonNull UUID id) throws SqlException {
		return transaction.from(USERS).select().where(Sql.equalTo(USER_ID, id)).fetchOneOrNull();
	}
	
	public @Nullable User findByDisplayName(@NonNull SqlTransaction transaction, @NonNull String displayName) throws SqlException {
		return transaction.from(USERS).select().where(Sql.equalsIgnoreCase(USER_DISPLAY_NAME, displayName)).fetchOneOrNull();
	}
	
	/**
	 * Looks up a user by a <strong>verified</strong> email address only (server-spec recovery), so an
	 * unverified, unconfirmed address can never be used to take over an account.
	 */
	public @Nullable User findByVerifiedEmail(@NonNull SqlTransaction transaction, @NonNull String email) throws SqlException {
		return transaction.from(USERS).select()
			.where(Sql.equalsIgnoreCase(USER_EMAIL, email))
			.where(Sql.equalTo(USER_EMAIL_VERIFIED, true))
			.fetchOneOrNull();
	}
	
	public void setEmail(@NonNull SqlTransaction transaction, @NonNull UUID id, @NonNull String email, boolean verified) throws SqlException {
		transaction.from(USERS).update().set(USER_EMAIL, email).set(USER_EMAIL_VERIFIED, verified).where(Sql.equalTo(USER_ID, id)).execute();
	}
	
	public @NonNull List<User> findAll(@NonNull SqlTransaction transaction) throws SqlException {
		return transaction.from(USERS).select().orderBy(USER_DISPLAY_NAME.ascending()).fetch();
	}
	
	/**
	 * Locks the row for the remainder of the transaction, so the last-admin invariant can be evaluated
	 * and acted on without another transaction changing the answer underneath it (server-spec 7.1).
	 */
	public @Nullable User findForUpdate(@NonNull SqlTransaction transaction, @NonNull UUID id) throws SqlException {
		return transaction.from(USERS).select().where(Sql.equalTo(USER_ID, id)).forUpdate().fetchOneOrNull();
	}
	
	public @NonNull User create(@NonNull SqlTransaction transaction, @NonNull String displayName, @NonNull Role role, @NonNull Instant now) throws SqlException {
		User draft = new User(UUID.randomUUID(), displayName, role, now, false, null, false);
		return transaction.from(USERS).insert(draft).returning().getFirst();
	}
	
	public void updateRole(@NonNull SqlTransaction transaction, @NonNull UUID id, @NonNull Role role) throws SqlException {
		transaction.from(USERS).update().set(USER_ROLE, role).where(Sql.equalTo(USER_ID, id)).execute();
	}
	
	public void revoke(@NonNull SqlTransaction transaction, @NonNull UUID id) throws SqlException {
		transaction.from(USERS).update().set(USER_REVOKED, true).where(Sql.equalTo(USER_ID, id)).execute();
	}

	/**
	 * Undoes {@link #revoke}, bringing a kicked account back with its history intact (server-spec 7.2).
	 * <p>
	 * The row was never deleted, so the display name, role, email, statistics, streak and currency ledger
	 * are all still attached to this id and come back with it.
	 */
	public void reinstate(@NonNull SqlTransaction transaction, @NonNull UUID id) throws SqlException {
		transaction.from(USERS).update().set(USER_REVOKED, false).where(Sql.equalTo(USER_ID, id)).execute();
	}
	
	/**
	 * @return how many non-revoked admins exist, excluding {@code excluding} when non-null
	 */
	public int countActiveAdmins(@NonNull SqlTransaction transaction, @Nullable UUID excluding) throws SqlException {
		var query = transaction.from(USERS).select(Sql.count(USER_ID, false))
			.where(Sql.equalTo(USER_ROLE, Role.ADMIN))
			.where(Sql.equalTo(USER_REVOKED, false));
		if (excluding != null) {
			query = query.where(Sql.isDistinctFrom(USER_ID, excluding));
		}
		return query.fetchOne().intValue();
	}
	
	public boolean anyActiveAdminExists(@NonNull SqlTransaction transaction) throws SqlException {
		return this.countActiveAdmins(transaction, null) > 0;
	}
}
