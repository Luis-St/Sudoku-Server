package net.luis.sudoku.device;

import net.luis.sudoku.auth.SessionService;
import net.luis.sudoku.auth.SignatureVerifier;
import net.luis.sudoku.db.Database;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.repository.*;
import net.luis.sudoku.security.CodeGenerator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.List;
import java.util.UUID;

/**
 * Linking an additional device to an existing user (server-spec 6.4, 6.5).
 * <p>
 * The code is <strong>minted by the server</strong>, never computed on the device: a locally generated
 * code would be unverifiable. It is short-lived, single-use, and bound to the requesting user. Because
 * it is short enough to retype by hand, attempt limiting rather than length is what makes guessing
 * hopeless (spec 12) - that lives in the handler.
 */
public final class DeviceLinkService {
	
	private static final Logger log = LoggerFactory.getLogger(DeviceLinkService.class);
	
	/** Minutes, not hours: the code exists only for as long as it takes to walk to the other device. */
	public static final Duration LINK_CODE_TTL = Duration.ofMinutes(10);
	
	private final Database database;
	private final LinkCodeRepository linkCodes;
	private final DeviceRepository devices;
	private final UserRepository users;
	private final SessionService sessionService;
	private final SignatureVerifier signatureVerifier;
	private final CodeGenerator codes;
	private final Clock clock;
	
	public DeviceLinkService(@NonNull Database database, @NonNull LinkCodeRepository linkCodes,
	                         @NonNull DeviceRepository devices, @NonNull UserRepository users,
	                         @NonNull SessionService sessionService, @NonNull SignatureVerifier signatureVerifier,
	                         @NonNull CodeGenerator codes, @NonNull Clock clock) {
		this.database = database;
		this.linkCodes = linkCodes;
		this.devices = devices;
		this.users = users;
		this.sessionService = sessionService;
		this.signatureVerifier = signatureVerifier;
		this.codes = codes;
		this.clock = clock;
	}
	
	/**
	 * Mints a link code for the authenticated caller, superseding any outstanding one.
	 */
	public @NonNull LinkCode mint(@NonNull Principal actor) {
		Instant now = this.clock.instant();
		Instant expiresAt = now.plus(LINK_CODE_TTL);
		String code = CodeGenerator.normalize(this.codes.linkCode());
		
		this.database.execute(connection -> {
			// One live code per user: otherwise repeated taps would leave a growing set of valid codes.
			this.linkCodes.deleteUnusedForUser(connection, actor.userId());
			this.linkCodes.create(connection, code, actor.userId(), expiresAt, now);
		});
		
		log.info("Minted a device link code for user {}", actor.userId());
		return new LinkCode(code, expiresAt);
	}
	
	/**
	 * Links a new device to the user a code was issued for, and signs that device in.
	 * <p>
	 * The new device inherits the user's role automatically, because roles live on the user rather than
	 * the device (spec 6.4).
	 *
	 * @throws ApiException {@code LINK_CODE_INVALID} if the code is unknown, expired or spent;
	 *   {@code KEY_TAKEN} if the key is already registered
	 */
	public @NonNull Linked link(@NonNull String rawCode, byte @NonNull [] publicKey, @NonNull KeyAlgorithm algorithm,
	                            @NonNull String deviceLabel) {
		String code = CodeGenerator.normalize(rawCode);
		String label = RegistrationService.validateDeviceLabel(deviceLabel);
		RegistrationService.validatePublicKey(publicKey);
		this.signatureVerifier.requireParsable(algorithm, publicKey);
		Instant now = this.clock.instant();
		
		Linked linked = this.database.transaction(connection -> {
			UUID userId = this.linkCodes.consume(connection, code, now);
			if (userId == null) {
				throw new ApiException(ErrorCode.LINK_CODE_INVALID, "Link code is not valid");
			}
			
			User user = this.users.find(connection, userId);
			if (user == null || user.revoked()) {
				throw new ApiException(ErrorCode.USER_REVOKED, "This account has been removed");
			}
			if (this.devices.findByPublicKey(connection, publicKey) != null) {
				throw new ApiException(ErrorCode.KEY_TAKEN, "That device key is already registered");
			}
			
			Device device = this.devices.create(connection, user.id(), publicKey, algorithm, label, now);
			SessionService.Issued issued = this.sessionService.issue(connection, user.id(), device.id(), now);
			return new Linked(user, device, issued.session(), issued.displaced());
		});
		
		// Linking signs the new device in, which by the single-session rule signs the old one out.
		this.sessionService.announceSuperseded(linked.displaced());
		log.info("Linked device {} to user {} ({})", linked.device().id(), linked.user().displayName(), linked.user().id());
		return linked;
	}
	
	/**
	 * @return the caller's devices, most recently created last
	 */
	public @NonNull List<Device> list(@NonNull Principal actor) {
		return this.database.read(connection -> this.devices.findByUser(connection, actor.userId()));
	}
	
	/**
	 * @param code the code to type on the new device
	 * @param expiresAt when it stops working
	 */
	public record LinkCode(@NonNull String code, @NonNull Instant expiresAt) {}
	
	/**
	 * @param user the user the device now belongs to
	 * @param device the newly linked device
	 * @param session the session issued to it
	 * @param displaced any session this replaced
	 */
	public record Linked(@NonNull User user, @NonNull Device device, @NonNull Session session,
	                     @Nullable Session displaced) {}
}
