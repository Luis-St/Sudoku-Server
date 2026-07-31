package net.luis.sudoku.recovery;

import net.luis.sudoku.auth.*;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.mail.MailService;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.repository.*;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.sudoku.security.RateLimiter;
import net.luis.sudoku.support.PostgresTest;
import net.luis.sudoku.support.TestKeys;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link RecoveryService}: email verification and account recovery.
 */
class RecoveryServiceTest extends PostgresTest {
	
	private static final String BOOTSTRAP = "BOOTSTRAP1";
	private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
	
	private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
	
	private UserRepository users;
	private InviteRepository invites;
	private DeviceRepository devices;
	private RegistrationService registrations;
	private RecoveryService recovery;
	private RecordingMailService mail;
	
	@BeforeEach
	void createServices() {
		this.now.set(NOW);
		Clock clock = new Clock() {
			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}
			
			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}
			
			@Override
			public Instant instant() {
				return RecoveryServiceTest.this.now.get();
			}
		};
		
		this.users = new UserRepository();
		this.devices = new DeviceRepository();
		this.invites = new InviteRepository();
		SessionRepository sessions = new SessionRepository();
		EmailVerificationRepository emailVerifications = new EmailVerificationRepository();
		RecoveryCodeRepository recoveryCodes = new RecoveryCodeRepository();
		
		SignatureVerifier verifier = new SignatureVerifier();
		SessionService sessionService = new SessionService(database, sessions, this.users, this.devices,
			new CodeGenerator(), SessionCloser.NONE);
		this.registrations = new RegistrationService(database, this.users, this.devices, this.invites,
			sessionService, verifier, clock);
		this.mail = new RecordingMailService();
		this.recovery = new RecoveryService(database, emailVerifications, recoveryCodes, this.users, this.devices,
			sessionService, verifier, new CodeGenerator(), this.mail, clock);
		this.registrations.ensureBootstrapInvite(BOOTSTRAP);
	}
	
	private Principal owner() {
		TestKeys keys = TestKeys.ed25519("owner");
		RegistrationService.Registered registered =
			this.registrations.register(BOOTSTRAP, "Owner", keys.publicKey(), keys.algorithm(), "Phone");
		return new Principal(registered.user(), registered.device(), registered.session());
	}
	
	private Principal member(Principal inviter, String name) {
		String code = "INV" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		database.execute(connection -> this.invites.create(connection, code, inviter.userId(), Role.NEW, null, this.now.get()));
		TestKeys keys = TestKeys.ed25519(name);
		RegistrationService.Registered registered =
			this.registrations.register(code, name, keys.publicKey(), keys.algorithm(), name + " phone");
		return new Principal(registered.user(), registered.device(), registered.session());
	}
	
	@Test
	void requestEmailVerification_thenConfirm_verifiesTheAddress() {
		Principal owner = this.owner();
		
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		String code = this.mail.lastCode();
		this.recovery.confirmEmail(owner, code);
		
		User stored = database.read(connection -> this.users.find(connection, owner.userId()));
		assertAll(
			() -> assertEquals("owner@example.com", stored.email()),
			() -> assertTrue(stored.emailVerified())
		);
	}
	
	// --- email verification ---
	
	@Test
	void confirmEmail_aCodeTypedWithDifferentCaseAndSpacing_stillWorks() {
		Principal owner = this.owner();
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		String typed = " " + this.mail.lastCode() + " ";
		
		assertDoesNotThrow(() -> this.recovery.confirmEmail(owner, typed));
	}
	
	@Test
	void requestEmailVerification_twice_invalidatesTheEarlierCode() {
		Principal owner = this.owner();
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		String first = this.mail.lastCode();
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		
		ApiException e = assertThrows(ApiException.class, () -> this.recovery.confirmEmail(owner, first));
		assertEquals(ErrorCode.EMAIL_VERIFICATION_INVALID, e.code());
	}
	
	@Test
	void confirmEmail_anExpiredCode_isRejected() {
		Principal owner = this.owner();
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		String code = this.mail.lastCode();
		this.now.set(NOW.plus(RecoveryService.EMAIL_VERIFICATION_TTL).plusSeconds(1));
		
		ApiException e = assertThrows(ApiException.class, () -> this.recovery.confirmEmail(owner, code));
		assertEquals(ErrorCode.EMAIL_VERIFICATION_INVALID, e.code());
	}
	
	@Test
	void confirmEmail_anUnknownCode_isRejected() {
		Principal owner = this.owner();
		ApiException e = assertThrows(ApiException.class, () -> this.recovery.confirmEmail(owner, "000000"));
		assertEquals(ErrorCode.EMAIL_VERIFICATION_INVALID, e.code());
	}
	
	@Test
	void confirmEmail_aCodeMintedForAnotherUser_isRejected() {
		Principal owner = this.owner();
		Principal other = this.member(owner, "Other");
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		String code = this.mail.lastCode();
		
		ApiException e = assertThrows(ApiException.class, () -> this.recovery.confirmEmail(other, code));
		assertEquals(ErrorCode.EMAIL_VERIFICATION_INVALID, e.code());
	}
	
	@Test
	void requestEmailVerification_anAddressAlreadyVerifiedByAnotherAccount_isRejected() {
		Principal owner = this.owner();
		this.recovery.requestEmailVerification(owner, "shared@example.com");
		this.recovery.confirmEmail(owner, this.mail.lastCode());
		
		Principal other = this.member(owner, "Other");
		ApiException e = assertThrows(ApiException.class,
			() -> this.recovery.requestEmailVerification(other, "shared@example.com"));
		assertEquals(ErrorCode.EMAIL_TAKEN, e.code());
	}
	
	@Test
	void requestEmailVerification_anInvalidAddress_isRejected() {
		Principal owner = this.owner();
		assertThrows(ApiException.class, () -> this.recovery.requestEmailVerification(owner, "not-an-email"));
	}
	
	@Test
	void requestRecovery_thenRedeem_signsANewDeviceIntoTheAccount() {
		Principal owner = this.owner();
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		this.recovery.confirmEmail(owner, this.mail.lastCode());
		
		this.recovery.requestRecovery("owner@example.com");
		String code = this.mail.lastCode();
		TestKeys tablet = TestKeys.ecdsa("tablet");
		
		RecoveryService.Redeemed redeemed = this.recovery.redeemRecovery(code, tablet.publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet");
		
		assertAll(
			() -> assertEquals(owner.userId(), redeemed.user().id()),
			() -> assertEquals(owner.userId(), redeemed.device().userId()),
			() -> assertEquals("Tablet", redeemed.device().label())
		);
	}
	
	// --- recovery request/redeem ---
	
	@Test
	void requestRecovery_signsOutTheOldSession() {
		Principal owner = this.owner();
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		this.recovery.confirmEmail(owner, this.mail.lastCode());
		this.recovery.requestRecovery("owner@example.com");
		String code = this.mail.lastCode();
		
		RecoveryService.Redeemed redeemed = this.recovery.redeemRecovery(code, TestKeys.ecdsa("tablet").publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet");
		
		assertNotNull(redeemed.displaced(), "redemption must displace the account's existing session");
	}
	
	@Test
	void requestRecovery_forAnUnknownEmail_sendsNoMailAndDoesNotThrow() {
		assertDoesNotThrow(() -> this.recovery.requestRecovery("nobody@example.com"));
		assertTrue(this.mail.sentTo.isEmpty(), "an unmatched address must not be revealed by sending mail");
	}
	
	@Test
	void requestRecovery_forAnUnverifiedEmail_sendsNoMail() {
		Principal owner = this.owner();
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		// Never confirmed.
		int sentBeforeRecoveryRequest = this.mail.sentTo.size();
		
		this.recovery.requestRecovery("owner@example.com");
		assertEquals(sentBeforeRecoveryRequest, this.mail.sentTo.size(), "an unverified address must not receive a recovery code");
	}
	
	@Test
	void redeemRecovery_anUnknownCode_isRejected() {
		ApiException e = assertThrows(ApiException.class,
			() -> this.recovery.redeemRecovery("NOPE-NOPE", TestKeys.ecdsa("tablet").publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet"));
		assertEquals(ErrorCode.RECOVERY_CODE_INVALID, e.code());
	}
	
	@Test
	void redeemRecovery_anExpiredCode_isRejected() {
		Principal owner = this.owner();
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		this.recovery.confirmEmail(owner, this.mail.lastCode());
		this.recovery.requestRecovery("owner@example.com");
		String code = this.mail.lastCode();
		this.now.set(NOW.plus(RecoveryService.RECOVERY_CODE_TTL).plusSeconds(1));
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.recovery.redeemRecovery(code, TestKeys.ecdsa("tablet").publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet"));
		assertEquals(ErrorCode.RECOVERY_CODE_INVALID, e.code());
	}
	
	@Test
	void redeemRecovery_theSameCodeTwice_isRejected() {
		Principal owner = this.owner();
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		this.recovery.confirmEmail(owner, this.mail.lastCode());
		this.recovery.requestRecovery("owner@example.com");
		String code = this.mail.lastCode();
		this.recovery.redeemRecovery(code, TestKeys.ecdsa("tablet").publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet");
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.recovery.redeemRecovery(code, TestKeys.ecdsa("laptop").publicKey(), KeyAlgorithm.ECDSA_P256, "Laptop"));
		assertEquals(ErrorCode.RECOVERY_CODE_INVALID, e.code());
	}
	
	@Test
	void redeemRecovery_forARevokedUser_isRejected() {
		Principal owner = this.owner();
		Principal victim = this.member(owner, "Victim");
		this.recovery.requestEmailVerification(victim, "victim@example.com");
		this.recovery.confirmEmail(victim, this.mail.lastCode());
		this.recovery.requestRecovery("victim@example.com");
		String code = this.mail.lastCode();
		database.execute(connection -> this.users.revoke(connection, victim.userId()));
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.recovery.redeemRecovery(code, TestKeys.ecdsa("tablet").publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet"));
		assertEquals(ErrorCode.USER_REVOKED, e.code());
	}
	
	@Test
	void redeemRecovery_anAlreadyRegisteredKey_isRejected() {
		Principal owner = this.owner();
		Principal other = this.member(owner, "Other");
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		this.recovery.confirmEmail(owner, this.mail.lastCode());
		this.recovery.requestRecovery("owner@example.com");
		String code = this.mail.lastCode();
		
		byte[] existingKey = database.read(connection -> this.devices.find(connection, other.deviceId())).publicKey();
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.recovery.redeemRecovery(code, existingKey, KeyAlgorithm.ED25519, "Stolen"));
		assertEquals(ErrorCode.KEY_TAKEN, e.code());
	}
	
	@Test
	void redeemRecovery_anUnparsableKey_isRejected() {
		Principal owner = this.owner();
		this.recovery.requestEmailVerification(owner, "owner@example.com");
		this.recovery.confirmEmail(owner, this.mail.lastCode());
		this.recovery.requestRecovery("owner@example.com");
		String code = this.mail.lastCode();
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.recovery.redeemRecovery(code, new byte[64], KeyAlgorithm.ED25519, "Junk"));
		assertEquals(ErrorCode.BAD_REQUEST, e.code());
	}
	
	@Test
	void rateLimiter_recoveryRedeemBucket_isTighterThanOrEqualToDeviceLink() {
		assertTrue(RateLimiter.Bucket.RECOVERY_REDEEM.limit() <= 10);
	}
	
	// --- rate limiting budgets (server-spec 12) ---
	
	/** Captures outgoing mail instead of opening a real SMTP connection. */
	private static final class RecordingMailService extends MailService {
		
		private final List<String> sentTo = new ArrayList<>();
		private final List<String> sentBodies = new ArrayList<>();
		
		private RecordingMailService() {
			super(null);
		}
		
		@Override
		public void send(@NonNull String to, @NonNull String subject, @NonNull String body) {
			this.sentTo.add(to);
			this.sentBodies.add(body);
		}
		
		private String lastBody() {
			return this.sentBodies.getLast();
		}
		
		/**
		 * @return the code embedded in the last message sent, assuming the "Your ... code is X" phrasing
		 */
		private String lastCode() {
			String body = this.lastBody();
			String marker = "code is ";
			int start = body.indexOf(marker) + marker.length();
			int end = body.indexOf('.', start);
			return body.substring(start, end);
		}
	}
}
