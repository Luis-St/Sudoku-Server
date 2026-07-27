package net.luis.sudoku.repository;

import net.luis.sudoku.domain.Invite;
import net.luis.sudoku.permission.Role;
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
 * Reads and writes {@code invites}.
 */
public final class InviteRepository {
	
	public @Nullable Invite find(@NonNull SqlTransaction transaction, @NonNull String code) throws SqlException {
		return transaction.from(INVITES).select().where(Sql.equalTo(INVITE_CODE, code)).fetchOneOrNull();
	}
	
	public @NonNull List<Invite> findByCreator(@NonNull SqlTransaction transaction, @Nullable UUID creator) throws SqlException {
		var query = transaction.from(INVITES).select();
		if (creator != null) {
			query = query.where(Sql.equalTo(INVITE_CREATED_BY, creator));
		}
		return query.orderBy(INVITE_CREATED_AT.descending()).fetch();
	}
	
	public @NonNull Invite create(@NonNull SqlTransaction transaction, @NonNull String code, @Nullable UUID createdBy, @NonNull Role grantsRole, @Nullable Instant expiresAt, @NonNull Instant now) throws SqlException {
		Invite draft = new Invite(code, createdBy, grantsRole, expiresAt, null, null, false, now);
		return transaction.from(INVITES).insert(draft).returning().getFirst();
	}
	
	/**
	 * Ensures the configured bootstrap invite exists, without disturbing it if it has already been used.
	 * <p>
	 * {@code DO NOTHING} matters: re-inserting on every boot would un-consume the invite and re-open the
	 * admin claim forever.
	 */
	public void ensureBootstrapInvite(@NonNull SqlTransaction transaction, @NonNull String code, @NonNull Instant now) throws SqlException {
		Invite draft = new Invite(code, null, Role.ADMIN, null, null, null, false, now);
		transaction.from(INVITES).insert(draft, INVITE_CODE).execute();
	}
	
	/**
	 * Burns an invite as a single conditional update.
	 * <p>
	 * The {@code consumed_at IS NULL} predicate is what makes double-consumption impossible without any
	 * locking (server-spec 5.1): two concurrent redemptions both run the UPDATE, and exactly one reports
	 * a row changed.
	 *
	 * @return true if this caller burned it, false if it was already gone
	 */
	public boolean consume(@NonNull SqlTransaction transaction, @NonNull String code, @NonNull UUID deviceId, @NonNull Instant now) throws SqlException {
		return transaction.from(INVITES).update()
			.set(INVITE_CONSUMED_BY_DEVICE, deviceId)
			.set(INVITE_CONSUMED_AT, now)
			.where(Sql.equalTo(INVITE_CODE, code))
			.where(Sql.isNull(INVITE_CONSUMED_AT))
			.where(Sql.equalTo(INVITE_REVOKED, false))
			.where(SqlCondition.anyOf(Sql.isNull(INVITE_EXPIRES_AT), Sql.greaterThan(INVITE_EXPIRES_AT, now)))
			.execute() == 1;
	}
	
	public boolean revoke(@NonNull SqlTransaction transaction, @NonNull String code) throws SqlException {
		return transaction.from(INVITES).update().set(INVITE_REVOKED, true)
			.where(Sql.equalTo(INVITE_CODE, code)).where(Sql.isNull(INVITE_CONSUMED_AT)).execute() == 1;
	}
}
