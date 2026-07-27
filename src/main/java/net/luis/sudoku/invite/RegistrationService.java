package net.luis.sudoku.invite;

import net.luis.sudoku.auth.SessionService;
import net.luis.sudoku.auth.SignatureVerifier;
import net.luis.sudoku.db.AdvisoryLocks;
import net.luis.sudoku.db.Database;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.repository.*;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;

/**
 * Registration by invite, including the one-time bootstrap-admin claim (server-spec 6.3).
 * <p>
 * The whole flow is one transaction: look up the invite, check the admin invariant if it is the
 * bootstrap invite, create the user and device, burn the invite, issue a session. A partial failure
 * must not leave a user without a device or an invite consumed by a user that was never created.
 */
public final class RegistrationService {
	
	private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
	
	private static final int MIN_DISPLAY_NAME = 2;
	private static final int MAX_DISPLAY_NAME = 32;
	private static final int MAX_DEVICE_LABEL = 64;
	
	private final Database database;
	private final UserRepository users;
	private final DeviceRepository devices;
	private final InviteRepository invites;
	private final SessionService sessionService;
	private final SignatureVerifier signatureVerifier;
	private final Clock clock;
	
	public RegistrationService(
		@NonNull Database database, @NonNull UserRepository users, @NonNull DeviceRepository devices, @NonNull InviteRepository invites, @NonNull SessionService sessionService,
		@NonNull SignatureVerifier signatureVerifier, @NonNull Clock clock
	) {
		this.database = database;
		this.users = users;
		this.devices = devices;
		this.invites = invites;
		this.sessionService = sessionService;
		this.signatureVerifier = signatureVerifier;
		this.clock = clock;
	}
	
	public static @NonNull String validateDisplayName(@NonNull String displayName) {
		String trimmed = displayName.trim();
		if (trimmed.length() < MIN_DISPLAY_NAME || trimmed.length() > MAX_DISPLAY_NAME) {
			throw ApiException.badRequest("Display name must be between " + MIN_DISPLAY_NAME + " and " + MAX_DISPLAY_NAME + " characters");
		}
		// Control characters would corrupt logs and render unpredictably in the client's player list.
		if (trimmed.chars().anyMatch(Character::isISOControl)) {
			throw ApiException.badRequest("Display name must not contain control characters");
		}
		return trimmed;
	}
	
	public static @NonNull String validateDeviceLabel(@NonNull String label) {
		String trimmed = label.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_DEVICE_LABEL) {
			throw ApiException.badRequest("Device label must be between 1 and " + MAX_DEVICE_LABEL + " characters");
		}
		if (trimmed.chars().anyMatch(Character::isISOControl)) {
			throw ApiException.badRequest("Device label must not contain control characters");
		}
		return trimmed;
	}
	
	public static void validatePublicKey(byte @NonNull [] publicKey) {
		// Bounds only; whether it parses as a key is the signature verifier's job (Phase 3).
		if (publicKey.length < 16 || publicKey.length > 512) {
			throw ApiException.badRequest("Public key length is implausible: " + publicKey.length + " bytes");
		}
	}
	
	/**
	 * Ensures the configured bootstrap invite row exists. Called once at startup.
	 */
	public void ensureBootstrapInvite(@NonNull String code) {
		String normalized = CodeGenerator.normalize(code);
		Instant now = this.clock.instant();
		this.database.execute(connection -> this.invites.ensureBootstrapInvite(connection, normalized, now));
	}
	
	/**
	 * Registers a new user and their first device against an invite.
	 *
	 * @throws ApiException {@code INVITE_INVALID} if the invite is missing, expired, revoked or spent;
	 *   {@code ADMIN_EXISTS} if a bootstrap claim arrives after an admin exists; {@code NAME_TAKEN} or
	 *   {@code KEY_TAKEN} on a collision
	 */
	public @NonNull Registered register(@NonNull String inviteCode, @NonNull String displayName, byte @NonNull [] publicKey, @NonNull KeyAlgorithm algorithm, @NonNull String deviceLabel) {
		String code = CodeGenerator.normalize(inviteCode);
		String name = validateDisplayName(displayName);
		String label = validateDeviceLabel(deviceLabel);
		validatePublicKey(publicKey);
		// Reject a key that could never produce a valid signature now, rather than at the first failed
		// login when the user has no way to tell what went wrong.
		this.signatureVerifier.requireParsable(algorithm, publicKey);
		Instant now = this.clock.instant();
		
		Registered registered = this.database.transaction(connection -> {
			Invite invite = this.invites.find(connection, code);
			if (invite == null || !invite.isLive(now)) {
				// Deliberately one code for every reason: telling an attacker whether a code exists but
				// expired, versus never existed, is free information.
				throw new ApiException(ErrorCode.INVITE_INVALID, "Invite code is not valid");
			}
			
			if (invite.isBootstrap()) {
				// Serialise the claim: without this, two clients redeeming the bootstrap code at the
				// same instant would both see zero admins and both become one (spec 5.1).
				try {
					Database.advisoryTransactionLock(connection.getConnection(), AdvisoryLocks.BOOTSTRAP_ADMIN);
				} catch (SQLException e) {
					throw new SqlException("Failed to take bootstrap-admin lock", e);
				}
				if (this.users.anyActiveAdminExists(connection)) {
					throw new ApiException(ErrorCode.ADMIN_EXISTS, "An administrator already exists on this server");
				}
			}
			
			if (this.users.findByDisplayName(connection, name) != null) {
				throw new ApiException(ErrorCode.NAME_TAKEN, "That display name is already taken");
			}
			if (this.devices.findByPublicKey(connection, publicKey) != null) {
				throw new ApiException(ErrorCode.KEY_TAKEN, "That device key is already registered");
			}
			
			User user = this.users.create(connection, name, invite.grantsRole(), now);
			Device device = this.devices.create(connection, user.id(), publicKey, algorithm, label, now);
			
			// Conditional burn: even with the checks above, this is what actually makes double
			// consumption impossible, because it re-tests liveness at write time (spec 5.1).
			if (!this.invites.consume(connection, code, device.id(), now)) {
				throw new ApiException(ErrorCode.INVITE_INVALID, "Invite code was consumed concurrently");
			}
			
			SessionService.Issued issued = this.sessionService.issue(connection, user.id(), device.id(), now);
			return new Registered(user, device, issued.session(), issued.displaced());
		});
		
		this.sessionService.announceSuperseded(registered.displaced());
		log.info("Registered user {} ({}) with role {} via invite {}", registered.user().displayName(),
			registered.user().id(), registered.user().role(), code);
		return registered;
	}
	
	/**
	 * @param user the created user
	 * @param device their first device
	 * @param session the issued session
	 * @param displaced any session this replaced, which is normally null during registration
	 */
	public record Registered(@NonNull User user, @NonNull Device device, @NonNull Session session, @Nullable Session displaced) {}
}
