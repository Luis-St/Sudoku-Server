package net.luis.sudoku.invite;

import net.luis.sudoku.auth.*;
import net.luis.sudoku.domain.KeyAlgorithm;
import net.luis.sudoku.domain.User;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.repository.*;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.sudoku.support.PostgresTest;
import net.luis.sudoku.support.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link RegistrationService}.
 */
class RegistrationServiceTest extends PostgresTest {
	
	private static final String BOOTSTRAP = "BOOT-STRA-P123";
	/**
	 * Registration parses the key, so these must be real. Cached because keypair generation is the
	 * slowest thing in this class by an order of magnitude.
	 */
	private static final Map<String, byte[]> KEY_CACHE = new ConcurrentHashMap<>();
	private final java.util.concurrent.atomic.AtomicInteger inviteCounter = new java.util.concurrent.atomic.AtomicInteger();
	private UserRepository users;
	private DeviceRepository devices;
	private InviteRepository invites;
	private RegistrationService registrations;
	private Clock clock;
	
	private static byte[] key(String seed) {
		return KEY_CACHE.computeIfAbsent(seed, label -> TestKeys.ed25519(label).publicKey()).clone();
	}
	
	@BeforeEach
	void createService() {
		this.clock = Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC);
		this.users = new UserRepository();
		this.devices = new DeviceRepository();
		this.invites = new InviteRepository();
		SessionService sessions = new SessionService(database, new SessionRepository(), this.users, this.devices,
			new CodeGenerator(), SessionCloser.NONE);
		this.registrations = new RegistrationService(database, this.users, this.devices, this.invites, sessions,
			new SignatureVerifier(), this.clock);
		this.registrations.ensureBootstrapInvite(BOOTSTRAP);
	}
	
	private String ordinaryInvite(java.util.UUID createdBy) {
		String code = "TEST-" + this.inviteCounter.incrementAndGet();
		String normalized = CodeGenerator.normalize(code);
		database.execute(connection -> this.invites.create(connection, normalized, createdBy, Role.NEW, null, this.clock.instant()));
		return normalized;
	}
	
	@Test
	void ensureBootstrapInvite_calledTwice_doesNotResurrectAConsumedInvite() {
		this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		
		// A restart re-runs this; it must not un-consume the invite and re-open the admin claim.
		this.registrations.ensureBootstrapInvite(BOOTSTRAP);
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.registrations.register(BOOTSTRAP, "Impostor", key("impostor"), KeyAlgorithm.ED25519, "Phone"));
		assertEquals(ErrorCode.INVITE_INVALID, e.code());
	}
	
	@Test
	void register_theBootstrapInvite_createsAnAdmin() {
		RegistrationService.Registered registered =
			this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		
		assertAll(
			() -> assertEquals(Role.ADMIN, registered.user().role()),
			() -> assertEquals("Admin", registered.user().displayName()),
			() -> assertFalse(registered.user().revoked()),
			() -> assertEquals(registered.user().id(), registered.device().userId()),
			() -> assertEquals(KeyAlgorithm.ED25519, registered.device().keyAlgorithm()),
			() -> assertFalse(registered.session().token().isBlank()),
			() -> assertNull(registered.displaced())
		);
	}
	
	@Test
	void register_aSecondBootstrapClaim_isRejectedWithAdminExists() {
		this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		// Re-open the code so the rejection is provably the admin check, not the consumed-invite check.
		database.execute(connection -> this.invites.ensureBootstrapInvite(connection, CodeGenerator.normalize(BOOTSTRAP + "2"), this.clock.instant()));
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.registrations.register(BOOTSTRAP + "2", "Second", key("second"), KeyAlgorithm.ED25519, "Tablet"));
		
		assertAll(
			() -> assertEquals(ErrorCode.ADMIN_EXISTS, e.code()),
			() -> assertEquals(403, e.status())
		);
	}
	
	@Test
	void register_aBootstrapClaimAfterTheOnlyAdminWasRevoked_isAllowedAgain() {
		RegistrationService.Registered first =
			this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		database.execute(connection -> this.users.revoke(connection, first.user().id()));
		database.execute(connection -> this.invites.ensureBootstrapInvite(connection, CodeGenerator.normalize(BOOTSTRAP + "2"), this.clock.instant()));
		
		// The break-glass path in spec 7.1: with no non-revoked admin, the claim legitimately reopens.
		RegistrationService.Registered second =
			this.registrations.register(BOOTSTRAP + "2", "Rescuer", key("rescuer"), KeyAlgorithm.ED25519, "Tablet");
		
		assertEquals(Role.ADMIN, second.user().role());
	}
	
	@Test
	void register_concurrentBootstrapClaims_produceExactlyOneAdmin() throws Exception {
		// The race spec 5.1 calls out by name: without pg_advisory_xact_lock both callers see zero
		// admins and both become one.
		int attempts = 6;
		for (int i = 0; i < attempts; i++) {
			String code = BOOTSTRAP + "-R" + i;
			database.execute(connection -> this.invites.ensureBootstrapInvite(connection, CodeGenerator.normalize(code), this.clock.instant()));
		}
		
		List<Callable<Boolean>> claims = new ArrayList<>();
		for (int i = 0; i < attempts; i++) {
			int index = i;
			claims.add(() -> {
				try {
					this.registrations.register(BOOTSTRAP + "-R" + index, "Claimant" + index, key("claimant" + index),
						KeyAlgorithm.ED25519, "Device" + index);
					return true;
				} catch (ApiException e) {
					assertEquals(ErrorCode.ADMIN_EXISTS, e.code());
					return false;
				}
			});
		}
		
		int won = 0;
		try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
			for (Future<Boolean> result : executor.invokeAll(claims)) {
				if (result.get()) {
					won++;
				}
			}
		}
		int succeeded = won;
		
		int admins = database.read(connection -> this.users.countActiveAdmins(connection, null));
		assertAll(
			() -> assertEquals(1, succeeded, "exactly one claim should have won"),
			() -> assertEquals(1, admins, "exactly one admin should exist")
		);
	}
	
	@Test
	void register_anOrdinaryInvite_createsANewRoleUser() {
		RegistrationService.Registered admin =
			this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		String code = this.ordinaryInvite(admin.user().id());
		
		RegistrationService.Registered member =
			this.registrations.register(code, "Player", TestKeys.ecdsa("player").publicKey(), KeyAlgorithm.ECDSA_P256, "Pixel");
		
		assertAll(
			() -> assertEquals(Role.NEW, member.user().role()),
			() -> assertEquals(KeyAlgorithm.ECDSA_P256, member.device().keyAlgorithm())
		);
	}
	
	@Test
	void register_thesameInviteTwice_isRejected() {
		RegistrationService.Registered admin =
			this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		String code = this.ordinaryInvite(admin.user().id());
		this.registrations.register(code, "First", key("first"), KeyAlgorithm.ED25519, "Pixel");
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.registrations.register(code, "Second", key("second"), KeyAlgorithm.ED25519, "Tablet"));
		assertEquals(ErrorCode.INVITE_INVALID, e.code());
	}
	
	@Test
	void register_concurrentRedemptionsOfOneInvite_burnItExactlyOnce() throws Exception {
		RegistrationService.Registered admin =
			this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		String code = this.ordinaryInvite(admin.user().id());
		
		int attempts = 6;
		List<Callable<Boolean>> redemptions = new ArrayList<>();
		for (int i = 0; i < attempts; i++) {
			int index = i;
			redemptions.add(() -> {
				try {
					this.registrations.register(code, "Racer" + index, key("racer" + index), KeyAlgorithm.ED25519, "D" + index);
					return true;
				} catch (ApiException e) {
					assertEquals(ErrorCode.INVITE_INVALID, e.code());
					return false;
				}
			});
		}
		
		int succeeded;
		try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
			succeeded = 0;
			for (Future<Boolean> result : executor.invokeAll(redemptions)) {
				if (result.get()) {
					succeeded++;
				}
			}
		}
		
		assertEquals(1, succeeded, "the conditional UPDATE must let exactly one redemption through");
	}
	
	@Test
	void register_anExpiredInvite_isRejected() {
		RegistrationService.Registered admin =
			this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		Instant past = this.clock.instant().minusSeconds(60);
		database.execute(connection -> this.invites.create(connection, "EXPIRED1", admin.user().id(), Role.NEW, past, this.clock.instant()));
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.registrations.register("EXPIRED1", "Late", key("late"), KeyAlgorithm.ED25519, "Phone"));
		assertEquals(ErrorCode.INVITE_INVALID, e.code());
	}
	
	@Test
	void register_aRevokedInvite_isRejected() {
		RegistrationService.Registered admin =
			this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		String code = this.ordinaryInvite(admin.user().id());
		database.execute(connection -> this.invites.revoke(connection, code));
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.registrations.register(code, "Blocked", key("blocked"), KeyAlgorithm.ED25519, "Phone"));
		assertEquals(ErrorCode.INVITE_INVALID, e.code());
	}
	
	@Test
	void register_anUnknownInvite_isRejected() {
		ApiException e = assertThrows(ApiException.class,
			() -> this.registrations.register("NOSU-CHCO-DE00", "Nobody", key("nobody"), KeyAlgorithm.ED25519, "Phone"));
		assertEquals(ErrorCode.INVITE_INVALID, e.code());
	}
	
	@Test
	void register_aTakenDisplayName_isRejectedRegardlessOfCase() {
		RegistrationService.Registered admin =
			this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		String code = this.ordinaryInvite(admin.user().id());
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.registrations.register(code, "aDmIn", key("other"), KeyAlgorithm.ED25519, "Tablet"));
		assertEquals(ErrorCode.NAME_TAKEN, e.code());
	}
	
	@Test
	void register_anAlreadyRegisteredKey_isRejected() {
		RegistrationService.Registered admin =
			this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		String code = this.ordinaryInvite(admin.user().id());
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.registrations.register(code, "Clone", key("admin"), KeyAlgorithm.ED25519, "Tablet"));
		assertEquals(ErrorCode.KEY_TAKEN, e.code());
	}
	
	@Test
	void register_aFailedRegistration_leavesNoUserBehind() {
		RegistrationService.Registered admin =
			this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		String code = this.ordinaryInvite(admin.user().id());
		
		// Collides on the key, which is checked after the name, so the transaction must roll back.
		assertThrows(ApiException.class,
			() -> this.registrations.register(code, "Rollback", key("admin"), KeyAlgorithm.ED25519, "Tablet"));
		
		List<User> all = database.read(connection -> this.users.findAll(connection));
		assertAll(
			() -> assertEquals(1, all.size()),
			() -> assertEquals("Admin", all.getFirst().displayName())
		);
	}
	
	@Test
	void register_aCodeTypedWithDifferentCaseAndSpacing_stillMatches() {
		this.registrations.register(" boot-stra-p123 ", "Admin", key("admin"), KeyAlgorithm.ED25519, "Phone");
		
		List<User> all = database.read(connection -> this.users.findAll(connection));
		assertEquals(1, all.size());
	}
	
	@Test
	void register_aDisplayNameOutsideTheAllowedLength_isRejected() {
		assertAll(
			() -> assertThrows(ApiException.class,
				() -> this.registrations.register(BOOTSTRAP, "A", key("a"), KeyAlgorithm.ED25519, "Phone")),
			() -> assertThrows(ApiException.class,
				() -> this.registrations.register(BOOTSTRAP, "x".repeat(33), key("b"), KeyAlgorithm.ED25519, "Phone"))
		);
	}
	
	@Test
	void register_aDisplayNameWithControlCharacters_isRejected() {
		ApiException e = assertThrows(ApiException.class,
			() -> this.registrations.register(BOOTSTRAP, "Ad" + (char) 7 + "min", key("a"), KeyAlgorithm.ED25519, "Phone"));
		assertEquals(ErrorCode.BAD_REQUEST, e.code());
	}
	
	@Test
	void register_anImplausiblePublicKey_isRejected() {
		assertAll(
			() -> assertThrows(ApiException.class,
				() -> this.registrations.register(BOOTSTRAP, "Tiny", new byte[4], KeyAlgorithm.ED25519, "Phone")),
			() -> assertThrows(ApiException.class,
				() -> this.registrations.register(BOOTSTRAP, "Huge", new byte[1024], KeyAlgorithm.ED25519, "Phone"))
		);
	}
	
	@Test
	void register_aBlankDeviceLabel_isRejectedByTheValidator() {
		// The handler substitutes a default before reaching here, so a blank arriving at the service is
		// a programming error rather than user input.
		assertThrows(ApiException.class,
			() -> this.registrations.register(BOOTSTRAP, "Admin", key("admin"), KeyAlgorithm.ED25519, "   "));
	}
}
