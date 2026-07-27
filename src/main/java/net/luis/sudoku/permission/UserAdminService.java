package net.luis.sudoku.permission;

import net.luis.sudoku.auth.SessionCloser;
import net.luis.sudoku.db.AdvisoryLocks;
import net.luis.sudoku.db.Database;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.domain.User;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.repository.*;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Role changes and kicks, both guarded by the admin invariant (server-spec 7.1, 7.2).
 * <p>
 * <strong>At least one non-revoked admin must always exist.</strong> The check and the mutation share a
 * transaction, and every path that could reduce the admin count first takes
 * {@link AdvisoryLocks#ADMIN_INVARIANT}. Row locking alone is not enough: {@code SELECT ... FOR UPDATE}
 * locks the user being changed, but the "is there another admin?" count reads other rows without
 * locking them, so two admins demoting each other would each still see the other and both commit.
 * <p>
 * A hard cap of exactly one admin is deliberately <em>not</em> imposed - that would make a lost key
 * unrecoverable.
 */
public final class UserAdminService {
	
	private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);
	
	private final Database database;
	private final UserRepository users;
	private final DeviceRepository devices;
	private final SessionRepository sessions;
	private final SessionCloser closer;
	
	public UserAdminService(@NonNull Database database, @NonNull UserRepository users, @NonNull DeviceRepository devices, @NonNull SessionRepository sessions, @NonNull SessionCloser closer) {
		this.database = database;
		this.users = users;
		this.devices = devices;
		this.sessions = sessions;
		this.closer = closer;
	}
	
	private static void lockAdminInvariant(@NonNull SqlTransaction connection) throws SqlException {
		try {
			Database.advisoryTransactionLock(connection.getConnection(), AdvisoryLocks.ADMIN_INVARIANT);
		} catch (SQLException e) {
			throw new SqlException("Failed to take admin-invariant lock", e);
		}
	}
	
	public @NonNull List<User> list() {
		return this.database.read(this.users::findAll);
	}
	
	public @NonNull User get(@NonNull UUID id) {
		User user = this.database.read(connection -> this.users.find(connection, id));
		if (user == null) {
			throw ApiException.notFound("No such user: " + id);
		}
		return user;
	}
	
	/**
	 * Changes a user's role.
	 *
	 * @throws ApiException {@code LAST_ADMIN} (409) if this would demote the only remaining admin
	 */
	public @NonNull User changeRole(@NonNull Principal actor, @NonNull UUID targetId, @NonNull Role role) {
		actor.require(Permission.CAN_CHANGE_ROLE);
		
		User updated = this.database.transaction(connection -> {
			// Serialise every admin-count-affecting change before reading anything (spec 7.1).
			lockAdminInvariant(connection);
			User target = this.users.findForUpdate(connection, targetId);
			if (target == null) {
				throw ApiException.notFound("No such user: " + targetId);
			}
			if (target.role() == role) {
				return target;
			}
			// Demoting an admin is the only direction that can breach the invariant.
			if (target.isAdmin() && role != Role.ADMIN && !target.revoked()) {
				this.requireAnotherAdminExists(connection, targetId);
			}
			
			this.users.updateRole(connection, targetId, role);
			return new User(target.id(), target.displayName(), role, target.createdAt(), target.revoked());
		});
		
		log.info("Admin action: {} ({}) changed role of {} ({}) to {}", actor.user().displayName(), actor.userId(),
			updated.displayName(), updated.id(), role);
		return updated;
	}
	
	/**
	 * Kicks a user (server-spec 7.2): marks them revoked, revokes <em>every</em> device key, deletes
	 * their session, and closes their sockets.
	 * <p>
	 * Revoking the keys is the part that matters. Merely dropping the connection would achieve nothing,
	 * because the client would reconnect with the same key. Historical results are retained; the user
	 * simply cannot authenticate again.
	 *
	 * @throws ApiException {@code LAST_ADMIN} (409) if this would remove the only remaining admin
	 */
	public void kick(@NonNull Principal actor, @NonNull UUID targetId) {
		actor.require(Permission.CAN_KICK);
		if (actor.userId().equals(targetId)) {
			throw new ApiException(ErrorCode.BAD_REQUEST, "You cannot kick yourself");
		}
		
		User target = this.database.transaction(connection -> {
			lockAdminInvariant(connection);
			User user = this.users.findForUpdate(connection, targetId);
			if (user == null) {
				throw ApiException.notFound("No such user: " + targetId);
			}
			if (user.revoked()) {
				return user;
			}
			if (user.isAdmin()) {
				this.requireAnotherAdminExists(connection, targetId);
			}
			
			this.users.revoke(connection, targetId);
			int revokedDevices = this.devices.revokeAllForUser(connection, targetId);
			this.sessions.deleteByUser(connection, targetId);
			log.info("Kick revoked {} device keys for user {}", revokedDevices, targetId);
			return user;
		});
		
		// After commit: if the transaction had rolled back, we would have disconnected a user who is
		// still perfectly entitled to be connected.
		this.closer.closeSocketsFor(targetId, ErrorCode.USER_REVOKED.name());
		log.info("Admin action: {} ({}) kicked {} ({})", actor.user().displayName(), actor.userId(),
			target.displayName(), target.id());
	}
	
	/**
	 * Revokes one device, guarding the case where it is the last key of the last admin.
	 *
	 * @return true if the caller revoked their own current device, meaning their session just ended
	 */
	public boolean revokeDevice(@NonNull Principal actor, @NonNull UUID deviceId) {
		boolean revokedOwn = this.database.transaction(connection -> {
			lockAdminInvariant(connection);
			var device = this.devices.find(connection, deviceId);
			if (device == null) {
				throw ApiException.notFound("No such device: " + deviceId);
			}
			// Anyone may drop their own device; removing someone else's is a kick-level action.
			if (!device.userId().equals(actor.userId())) {
				actor.require(Permission.CAN_KICK);
			}
			if (device.revoked()) {
				return false;
			}
			
			User owner = this.users.findForUpdate(connection, device.userId());
			if (owner != null && owner.isAdmin() && !owner.revoked() && this.devices.countActiveForUser(connection, owner.id()) <= 1) {
				// Losing the last key of the last admin locks everyone out of admin actions forever,
				// so it is refused for the same reason a demotion would be (spec 6.5, 7.1).
				this.requireAnotherAdminExists(connection, owner.id());
			}
			
			this.devices.revoke(connection, deviceId);
			this.sessions.deleteByDevice(connection, deviceId);
			return deviceId.equals(actor.deviceId());
		});
		
		if (revokedOwn) {
			this.closer.closeSocketsFor(actor.userId(), "DEVICE_REVOKED");
		}
		log.info("Admin action: {} ({}) revoked device {}", actor.user().displayName(), actor.userId(), deviceId);
		return revokedOwn;
	}
	
	private void requireAnotherAdminExists(@NonNull SqlTransaction connection, @NonNull UUID excluding) throws SqlException {
		if (this.users.countActiveAdmins(connection, excluding) == 0) {
			throw new ApiException(ErrorCode.LAST_ADMIN, "This would leave the server with no administrator. Promote someone else first.");
		}
	}
}
