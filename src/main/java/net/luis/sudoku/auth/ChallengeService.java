package net.luis.sudoku.auth;

import net.luis.sudoku.db.Database;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.repository.*;
import net.luis.sudoku.security.CodeGenerator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;

/**
 * The challenge-response handshake (server-spec 6.1).
 * <p>
 * There are no passwords: identity is a keypair held by the device, and authentication is a signature
 * over a server-issued nonce. The server therefore stores no secret whose leak would enable
 * impersonation (spec 12).
 */
public final class ChallengeService {
	
	private static final Logger log = LoggerFactory.getLogger(ChallengeService.class);
	
	/** Short enough that a captured nonce is near-useless, long enough for a slow mobile round trip. */
	public static final Duration CHALLENGE_TTL = Duration.ofSeconds(60);
	
	private final Database database;
	private final AuthChallengeRepository challenges;
	private final DeviceRepository devices;
	private final UserRepository users;
	private final SessionService sessionService;
	private final SignatureVerifier verifier;
	private final CodeGenerator codes;
	private final Clock clock;
	
	public ChallengeService(
		@NonNull Database database, @NonNull AuthChallengeRepository challenges, @NonNull DeviceRepository devices, @NonNull UserRepository users, @NonNull SessionService sessionService,
		@NonNull SignatureVerifier verifier, @NonNull CodeGenerator codes, @NonNull Clock clock
	) {
		this.database = database;
		this.challenges = challenges;
		this.devices = devices;
		this.users = users;
		this.sessionService = sessionService;
		this.verifier = verifier;
		this.codes = codes;
		this.clock = clock;
	}
	
	/**
	 * Issues a nonce for a known, non-revoked key.
	 *
	 * @throws ApiException {@code UNKNOWN_KEY} (404) if the key is not registered - the client should
	 *   register or link instead; {@code USER_REVOKED} (403) if the key or its user is revoked
	 */
	public @NonNull Challenge challenge(byte @NonNull [] publicKey) {
		Instant now = this.clock.instant();
		Instant expiresAt = now.plus(CHALLENGE_TTL);
		
		return this.database.transaction(connection -> {
			Device device = this.devices.findByPublicKey(connection, publicKey);
			if (device == null) {
				throw new ApiException(ErrorCode.UNKNOWN_KEY, "This device key is not registered");
			}
			if (device.revoked()) {
				throw new ApiException(ErrorCode.USER_REVOKED, "This device has been revoked");
			}
			
			User user = this.users.find(connection, device.userId());
			if (user == null || user.revoked()) {
				throw new ApiException(ErrorCode.USER_REVOKED, "This account has been removed");
			}
			
			// Requesting a new challenge invalidates any outstanding one, so a device cannot accumulate
			// live nonces by asking repeatedly.
			this.challenges.deleteForKey(connection, publicKey);
			
			byte[] nonce = this.codes.nonce();
			this.challenges.create(connection, nonce, publicKey, expiresAt);
			return new Challenge(nonce, expiresAt);
		});
	}
	
	/**
	 * Verifies a signature over a previously issued nonce and issues a session.
	 * <p>
	 * Every failure - unknown nonce, expired nonce, replayed nonce, wrong signature - returns the same
	 * {@code INVALID_SIGNATURE}, so a caller learns nothing about which part was wrong.
	 *
	 * @throws ApiException {@code INVALID_SIGNATURE} (401) on any verification failure
	 */
	public @NonNull Authenticated verify(byte @NonNull [] nonce, byte @NonNull [] signature) {
		Instant now = this.clock.instant();
		
		// Burning the nonce is a SEPARATE, COMMITTED transaction, deliberately. Doing it in the same
		// transaction as the signature check would undo the delete on rollback, so a wrong signature
		// would leave the nonce alive and an attacker could keep guessing against one challenge until
		// it expired. Committing first makes every attempt - right or wrong - cost a fresh round trip.
		byte[] publicKey = this.database.transaction(connection -> this.challenges.consume(connection, nonce, now));
		if (publicKey == null) {
			throw new ApiException(ErrorCode.INVALID_SIGNATURE, "Challenge is unknown, expired, or already used");
		}
		
		Authenticated authenticated = this.database.transaction(connection -> {
			Device device = this.devices.findByPublicKey(connection, publicKey);
			if (device == null || device.revoked()) {
				throw new ApiException(ErrorCode.INVALID_SIGNATURE, "Challenge is no longer valid for this device");
			}
			
			User user = this.users.find(connection, device.userId());
			if (user == null || user.revoked()) {
				throw new ApiException(ErrorCode.USER_REVOKED, "This account has been removed");
			}
			
			if (!this.verifier.verify(device.keyAlgorithm(), publicKey, nonce, signature)) {
				throw new ApiException(ErrorCode.INVALID_SIGNATURE, "Signature does not match the challenge");
			}
			
			SessionService.Issued issued = this.sessionService.issue(connection, user.id(), device.id(), now);
			return new Authenticated(user, device, issued.session(), issued.displaced());
		});
		
		this.sessionService.announceSuperseded(authenticated.displaced());
		log.info("User {} ({}) authenticated on device {}", authenticated.user().displayName(), authenticated.user().id(), authenticated.device().id());
		return authenticated;
	}
	
	/**
	 * @param nonce 32 random bytes the device must sign
	 * @param expiresAt when the nonce stops being accepted
	 */
	public record Challenge(byte @NonNull [] nonce, @NonNull Instant expiresAt) {
		
		public Challenge {
			nonce = nonce.clone();
		}
		
		@Override
		public byte @NonNull [] nonce() {
			return this.nonce.clone();
		}
	}
	
	/**
	 * @param user the authenticated user
	 * @param device the device that signed
	 * @param session the freshly issued session
	 * @param displaced any session this replaced
	 */
	public record Authenticated(@NonNull User user, @NonNull Device device, @NonNull Session session, @Nullable Session displaced) {}
}
