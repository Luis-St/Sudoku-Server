package net.luis.sudoku.recovery;

import net.luis.sudoku.auth.SessionService;
import net.luis.sudoku.auth.SignatureVerifier;
import net.luis.sudoku.db.Database;
import net.luis.sudoku.db.schema.Schema;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.mail.MailService;
import net.luis.sudoku.repository.*;
import net.luis.sudoku.security.CodeGenerator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Email-based account recovery: verifying an email against the caller's account, and using a verified
 * email to take over the account from a fresh, unauthenticated device.
 * <p>
 * This is the email analogue of {@link net.luis.sudoku.device.DeviceLinkService}: a recovery code is
 * minted by the server, short-lived, single-use, and bound to one user. Unlike a link code, it can be
 * redeemed with no existing session at all, which is the entire point of recovery - it is what a user
 * has left once every device is lost.
 */
public final class RecoveryService {
	
	private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);
	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final int MAX_EMAIL_LENGTH = 254;
	/** Long enough to read an email and type the code back; short enough to keep guessing hopeless. */
	public static final Duration EMAIL_VERIFICATION_TTL = Duration.ofMinutes(30);
	/** Minutes, not hours: whoever redeems it takes over the account immediately. */
	public static final Duration RECOVERY_CODE_TTL = Duration.ofMinutes(15);
	private final Database database;
	private final EmailVerificationRepository emailVerifications;
	private final RecoveryCodeRepository recoveryCodes;
	private final UserRepository users;
	private final DeviceRepository devices;
	private final SessionService sessionService;
	private final SignatureVerifier signatureVerifier;
	private final CodeGenerator codes;
	private final MailService mail;
	private final Clock clock;
	
	public RecoveryService(
		@NonNull Database database, @NonNull EmailVerificationRepository emailVerifications, @NonNull RecoveryCodeRepository recoveryCodes, @NonNull UserRepository users,
		@NonNull DeviceRepository devices, @NonNull SessionService sessionService, @NonNull SignatureVerifier signatureVerifier, @NonNull CodeGenerator codes,
		@NonNull MailService mail, @NonNull Clock clock
	) {
		this.database = database;
		this.emailVerifications = emailVerifications;
		this.recoveryCodes = recoveryCodes;
		this.users = users;
		this.devices = devices;
		this.sessionService = sessionService;
		this.signatureVerifier = signatureVerifier;
		this.codes = codes;
		this.mail = mail;
		this.clock = clock;
	}
	
	public static @NonNull String validateEmail(@NonNull String email) {
		String trimmed = email.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(trimmed).matches()) {
			throw ApiException.badRequest("Not a valid email address");
		}
		return trimmed;
	}
	
	/**
	 * Starts verifying that the caller owns {@code email}, superseding any outstanding verification.
	 *
	 * @throws ApiException {@code EMAIL_TAKEN} if another account has already verified that address;
	 *   {@code MAIL_NOT_CONFIGURED} if this server has no SMTP settings
	 */
	public void requestEmailVerification(@NonNull Principal actor, @NonNull String email) {
		String normalized = validateEmail(email);
		Instant now = this.clock.instant();
		Instant expiresAt = now.plus(EMAIL_VERIFICATION_TTL);
		String code = this.codes.emailVerificationCode();
		
		this.database.execute(connection -> {
			User existing = this.users.findByVerifiedEmail(connection, normalized);
			if (existing != null && !existing.id().equals(actor.userId())) {
				throw new ApiException(ErrorCode.EMAIL_TAKEN, "That email is already registered to another account");
			}
			// One live code per user: otherwise repeated taps would leave a growing set of valid codes.
			this.emailVerifications.deleteUnusedForUser(connection, actor.userId());
			this.emailVerifications.create(connection, code, actor.userId(), normalized, expiresAt, now);
		});
		
		this.mail.send(normalized, "Verify your email", "Your verification code is " + code + ". It expires in 30 minutes.");
		log.info("Requested email verification for user {}", actor.userId());
	}
	
	/**
	 * Confirms a pending email verification for the caller.
	 *
	 * @throws ApiException {@code EMAIL_VERIFICATION_INVALID} if the code is unknown, expired, spent, or
	 *   not the caller's
	 */
	public void confirmEmail(@NonNull Principal actor, @NonNull String rawCode) {
		String code = CodeGenerator.normalize(rawCode);
		Instant now = this.clock.instant();
		
		this.database.execute(connection -> {
			Schema.EmailVerificationRow verification = this.emailVerifications.consume(connection, code, actor.userId(), now);
			if (verification == null) {
				throw new ApiException(ErrorCode.EMAIL_VERIFICATION_INVALID, "Verification code is not valid");
			}
			this.users.setEmail(connection, actor.userId(), verification.email(), true);
		});
		
		log.info("Confirmed email for user {}", actor.userId());
	}
	
	/**
	 * Requests a recovery code for whichever account (if any) has verified {@code email}.
	 * <p>
	 * Always succeeds from the caller's point of view - the response is deliberately identical whether
	 * or not the address matched, so an attacker cannot use this endpoint to enumerate accounts.
	 */
	public void requestRecovery(@NonNull String email) {
		String normalized;
		try {
			normalized = validateEmail(email);
		} catch (ApiException e) {
			// An invalid address cannot match anything either; still say nothing.
			return;
		}
		Instant now = this.clock.instant();
		Instant expiresAt = now.plus(RECOVERY_CODE_TTL);
		String code = CodeGenerator.normalize(this.codes.recoveryCode());
		
		UUID userId = this.database.transaction(connection -> {
			User user = this.users.findByVerifiedEmail(connection, normalized);
			if (user == null || user.revoked()) {
				return null;
			}
			// One live code per user: otherwise repeated taps would leave a growing set of valid codes.
			this.recoveryCodes.deleteUnusedForUser(connection, user.id());
			this.recoveryCodes.create(connection, code, user.id(), expiresAt, now);
			return user.id();
		});
		
		if (userId == null) {
			log.info("Recovery requested for an email with no verified match");
			return;
		}
		
		this.mail.send(normalized, "Account recovery", "Your recovery code is " + code + ". It expires in 15 minutes.");
		log.info("Minted a recovery code for user {}", userId);
	}
	
	/**
	 * Redeems a recovery code on a fresh, unauthenticated device, handing it the account.
	 *
	 * @throws ApiException {@code RECOVERY_CODE_INVALID} if the code is unknown, expired or spent;
	 *   {@code USER_REVOKED} if the account has been kicked; {@code KEY_TAKEN} if the key is already
	 *   registered
	 */
	public @NonNull Redeemed redeemRecovery(@NonNull String rawCode, byte @NonNull [] publicKey, @NonNull KeyAlgorithm algorithm, @NonNull String deviceLabel) {
		String code = CodeGenerator.normalize(rawCode);
		String label = RegistrationService.validateDeviceLabel(deviceLabel);
		RegistrationService.validatePublicKey(publicKey);
		this.signatureVerifier.requireParsable(algorithm, publicKey);
		Instant now = this.clock.instant();
		
		Redeemed redeemed = this.database.transaction(connection -> {
			UUID userId = this.recoveryCodes.consume(connection, code, now);
			if (userId == null) {
				throw new ApiException(ErrorCode.RECOVERY_CODE_INVALID, "Recovery code is not valid");
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
			return new Redeemed(user, device, issued.session(), issued.displaced());
		});
		
		// Redemption signs the new device in, which by the single-session rule signs the old one out.
		this.sessionService.announceSuperseded(redeemed.displaced());
		log.info("Recovered account {} ({}) onto device {}", redeemed.user().displayName(), redeemed.user().id(), redeemed.device().id());
		return redeemed;
	}
	
	/**
	 * @param user the recovered user
	 * @param device the newly registered device
	 * @param session the session issued to it
	 * @param displaced any session this replaced
	 */
	public record Redeemed(@NonNull User user, @NonNull Device device, @NonNull Session session, @Nullable Session displaced) {}
}
