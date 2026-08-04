package net.luis.sudoku.repository;

import net.luis.sudoku.domain.Device;
import net.luis.sudoku.domain.KeyAlgorithm;
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
 * Reads and writes {@code devices}.
 */
public final class DeviceRepository {
	
	public @Nullable Device find(@NonNull SqlTransaction transaction, @NonNull UUID id) throws SqlException {
		return transaction.from(DEVICES).select().where(Sql.equalTo(DEVICE_ID, id)).fetchOneOrNull();
	}
	
	public @Nullable Device findByPublicKey(@NonNull SqlTransaction transaction, byte @NonNull [] publicKey) throws SqlException {
		return transaction.from(DEVICES).select().where(Sql.equalTo(DEVICE_PUBLIC_KEY, publicKey)).fetchOneOrNull();
	}
	
	public @NonNull List<Device> findByUser(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		return transaction.from(DEVICES).select().where(Sql.equalTo(DEVICE_USER_ID, userId))
			.orderBy(DEVICE_CREATED_AT.ascending()).fetch();
	}
	
	public @NonNull Device create(@NonNull SqlTransaction transaction, @NonNull UUID userId, byte @NonNull [] publicKey, @NonNull KeyAlgorithm algorithm, @NonNull String label, @NonNull Instant now) throws SqlException {
		Device draft = new Device(UUID.randomUUID(), userId, publicKey, algorithm, label, now, null, false, false);
		return transaction.from(DEVICES).insert(draft).returning().getFirst();
	}
	
	public void touch(@NonNull SqlTransaction transaction, @NonNull UUID id, @NonNull Instant at) throws SqlException {
		transaction.from(DEVICES).update().set(DEVICE_LAST_SEEN_AT, at).where(Sql.equalTo(DEVICE_ID, id)).execute();
	}
	
	/**
	 * Revokes one key deliberately - the owner dropping a device, or an admin removing one.
	 * <p>
	 * {@code revoked_by_kick} is left alone rather than cleared: this key is not coming back
	 * from a reinstatement, and a device that was kicked and then explicitly revoked has been refused
	 * twice, so the stricter of the two answers is the right one to keep.
	 */
	public void revoke(@NonNull SqlTransaction transaction, @NonNull UUID id) throws SqlException {
		transaction.from(DEVICES).update().set(DEVICE_REVOKED, true).where(Sql.equalTo(DEVICE_ID, id)).execute();
	}

	/**
	 * Revokes every key belonging to a user. This is what makes a kick stick: merely dropping the
	 * connection would let the client reconnect with the same key (server-spec 7.2).
	 * <p>
	 * Marks each one as kick-revoked, which is what {@link #restoreKickRevokedForUser} later restores.
	 * Only keys that are live right now are touched, so a device the owner had already dropped keeps
	 * {@code revokedByKick = false} and stays dead through a reinstatement.
	 *
	 * @return how many devices were revoked
	 */
	public int revokeAllForUser(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		return transaction.from(DEVICES).update().set(DEVICE_REVOKED, true).set(DEVICE_REVOKED_BY_KICK, true)
			.where(Sql.equalTo(DEVICE_USER_ID, userId)).where(Sql.equalTo(DEVICE_REVOKED, false)).execute();
	}

	/**
	 * Gives back exactly the keys a kick took, undoing {@link #revokeAllForUser} (server-spec 7.2).
	 *
	 * @return how many devices were restored
	 */
	public int restoreKickRevokedForUser(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		return transaction.from(DEVICES).update().set(DEVICE_REVOKED, false).set(DEVICE_REVOKED_BY_KICK, false)
			.where(Sql.equalTo(DEVICE_USER_ID, userId)).where(Sql.equalTo(DEVICE_REVOKED_BY_KICK, true)).execute();
	}

	public int countActiveForUser(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		return transaction.from(DEVICES).select(Sql.count(DEVICE_ID, false))
			.where(Sql.equalTo(DEVICE_USER_ID, userId)).where(Sql.equalTo(DEVICE_REVOKED, false))
			.fetchOne().intValue();
	}
}
