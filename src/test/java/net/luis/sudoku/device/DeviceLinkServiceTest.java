package net.luis.sudoku.device;

import net.luis.sudoku.auth.SessionCloser;
import net.luis.sudoku.auth.SessionService;
import net.luis.sudoku.auth.SignatureVerifier;
import net.luis.sudoku.domain.Device;
import net.luis.sudoku.domain.KeyAlgorithm;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.repository.DeviceRepository;
import net.luis.sudoku.repository.InviteRepository;
import net.luis.sudoku.repository.LinkCodeRepository;
import net.luis.sudoku.repository.SessionRepository;
import net.luis.sudoku.repository.UserRepository;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.sudoku.support.PostgresTest;
import net.luis.sudoku.support.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link DeviceLinkService}, covering server-spec 6.4 and 6.5.
 */
class DeviceLinkServiceTest extends PostgresTest {

	private static final String BOOTSTRAP = "BOOTSTRAP1";
	private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");

	private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

	private UserRepository users;
	private DeviceRepository devices;
	private InviteRepository invites;
	private SessionRepository sessions;
	private LinkCodeRepository linkCodes;
	private SessionService sessionService;
	private RegistrationService registrations;
	private DeviceLinkService links;

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
				return DeviceLinkServiceTest.this.now.get();
			}
		};

		this.users = new UserRepository();
		this.devices = new DeviceRepository();
		this.invites = new InviteRepository();
		this.sessions = new SessionRepository();
		this.linkCodes = new LinkCodeRepository();

		SignatureVerifier verifier = new SignatureVerifier();
		this.sessionService = new SessionService(database, this.sessions, this.users, this.devices,
			new CodeGenerator(), SessionCloser.NONE);
		this.registrations = new RegistrationService(database, this.users, this.devices, this.invites,
			this.sessionService, verifier, clock);
		this.links = new DeviceLinkService(database, this.linkCodes, this.devices, this.users, this.sessionService,
			verifier, new CodeGenerator(), clock);
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
	void mint_forAnAuthenticatedUser_returnsAShortCodeWithAMinutesTtl() {
		Principal owner = this.owner();

		DeviceLinkService.LinkCode code = this.links.mint(owner);

		assertAll(
			() -> assertFalse(code.code().isBlank()),
			() -> assertEquals(NOW.plus(DeviceLinkService.LINK_CODE_TTL), code.expiresAt()),
			() -> assertTrue(DeviceLinkService.LINK_CODE_TTL.toMinutes() <= 60, "the TTL must be minutes, not hours")
		);
	}

	@Test
	void mint_twice_invalidatesTheEarlierCode() {
		Principal owner = this.owner();
		DeviceLinkService.LinkCode first = this.links.mint(owner);
		this.links.mint(owner);

		TestKeys tablet = TestKeys.ecdsa("tablet");
		ApiException e = assertThrows(ApiException.class,
			() -> this.links.link(first.code(), tablet.publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet"));
		assertEquals(ErrorCode.LINK_CODE_INVALID, e.code());
	}

	@Test
	void link_withAValidCode_attachesTheDeviceToTheSameUser() {
		Principal owner = this.owner();
		DeviceLinkService.LinkCode code = this.links.mint(owner);
		TestKeys tablet = TestKeys.ecdsa("tablet");

		DeviceLinkService.Linked linked = this.links.link(code.code(), tablet.publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet");

		assertAll(
			() -> assertEquals(owner.userId(), linked.user().id()),
			() -> assertEquals(owner.userId(), linked.device().userId()),
			() -> assertEquals("Tablet", linked.device().label()),
			() -> assertEquals(KeyAlgorithm.ECDSA_P256, linked.device().keyAlgorithm())
		);
	}

	@Test
	void link_theNewDevice_inheritsTheUsersRole() {
		// Roles live on the user, so the new device is an admin's device immediately (spec 6.4).
		Principal owner = this.owner();
		DeviceLinkService.LinkCode code = this.links.mint(owner);
		TestKeys tablet = TestKeys.ecdsa("tablet");

		DeviceLinkService.Linked linked = this.links.link(code.code(), tablet.publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet");

		assertEquals(Role.ADMIN, linked.user().role());
	}

	@Test
	void link_signsTheNewDeviceIn_andDisplacesTheOldSession() {
		Principal owner = this.owner();
		DeviceLinkService.LinkCode code = this.links.mint(owner);
		TestKeys tablet = TestKeys.ecdsa("tablet");

		DeviceLinkService.Linked linked = this.links.link(code.code(), tablet.publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet");

		assertAll(
			() -> assertNotNull(this.sessionService.authenticate(linked.session().token(), NOW)),
			() -> assertThrows(ApiException.class, () -> this.sessionService.authenticate(owner.session().token(), NOW))
		);
	}

	@Test
	void link_withTheSameCodeTwice_isRejected() {
		Principal owner = this.owner();
		DeviceLinkService.LinkCode code = this.links.mint(owner);
		this.links.link(code.code(), TestKeys.ecdsa("tablet").publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet");

		TestKeys another = TestKeys.ecdsa("laptop");
		ApiException e = assertThrows(ApiException.class,
			() -> this.links.link(code.code(), another.publicKey(), KeyAlgorithm.ECDSA_P256, "Laptop"));
		assertEquals(ErrorCode.LINK_CODE_INVALID, e.code());
	}

	@Test
	void link_concurrentUseOfOneCode_succeedsExactlyOnce() throws Exception {
		Principal owner = this.owner();
		DeviceLinkService.LinkCode code = this.links.mint(owner);

		int attempts = 5;
		List<Callable<Boolean>> uses = new ArrayList<>();
		for (int i = 0; i < attempts; i++) {
			int index = i;
			uses.add(() -> {
				try {
					this.links.link(code.code(), TestKeys.ecdsa("racer" + index).publicKey(),
						KeyAlgorithm.ECDSA_P256, "Racer" + index);
					return true;
				} catch (ApiException e) {
					return false;
				}
			});
		}

		int won = 0;
		try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
			for (Future<Boolean> result : executor.invokeAll(uses)) {
				if (result.get()) {
					won++;
				}
			}
		}

		assertEquals(1, won, "a single-use code must link exactly one device");
	}

	@Test
	void link_anExpiredCode_isRejected() {
		Principal owner = this.owner();
		DeviceLinkService.LinkCode code = this.links.mint(owner);
		this.now.set(NOW.plus(DeviceLinkService.LINK_CODE_TTL).plusSeconds(1));

		ApiException e = assertThrows(ApiException.class,
			() -> this.links.link(code.code(), TestKeys.ecdsa("tablet").publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet"));
		assertEquals(ErrorCode.LINK_CODE_INVALID, e.code());
	}

	@Test
	void link_anUnknownCode_isRejected() {
		ApiException e = assertThrows(ApiException.class,
			() -> this.links.link("NOPE-NOPE", TestKeys.ecdsa("tablet").publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet"));
		assertEquals(ErrorCode.LINK_CODE_INVALID, e.code());
	}

	@Test
	void link_aCodeTypedWithDifferentCaseAndSpacing_stillWorks() {
		Principal owner = this.owner();
		DeviceLinkService.LinkCode code = this.links.mint(owner);
		String typed = " " + code.code().toLowerCase() + " ";

		assertDoesNotThrow(() ->
			this.links.link(typed, TestKeys.ecdsa("tablet").publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet"));
	}

	@Test
	void link_anAlreadyRegisteredKey_isRejected() {
		Principal owner = this.owner();
		Principal other = this.member(owner, "Other");
		DeviceLinkService.LinkCode code = this.links.mint(owner);

		byte[] existingKey = database.read(connection ->
			this.devices.find(connection, other.deviceId())).publicKey();

		ApiException e = assertThrows(ApiException.class,
			() -> this.links.link(code.code(), existingKey, KeyAlgorithm.ED25519, "Stolen"));
		assertEquals(ErrorCode.KEY_TAKEN, e.code());
	}

	@Test
	void link_forARevokedUser_isRejected() {
		Principal owner = this.owner();
		Principal victim = this.member(owner, "Victim");
		DeviceLinkService.LinkCode code = this.links.mint(victim);
		database.execute(connection -> this.users.revoke(connection, victim.userId()));

		ApiException e = assertThrows(ApiException.class,
			() -> this.links.link(code.code(), TestKeys.ecdsa("tablet").publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet"));
		assertEquals(ErrorCode.USER_REVOKED, e.code());
	}

	@Test
	void link_anUnparsableKey_isRejected() {
		Principal owner = this.owner();
		DeviceLinkService.LinkCode code = this.links.mint(owner);
		byte[] nonsense = new byte[64];

		ApiException e = assertThrows(ApiException.class,
			() -> this.links.link(code.code(), nonsense, KeyAlgorithm.ED25519, "Junk"));
		assertEquals(ErrorCode.BAD_REQUEST, e.code());
	}

	@Test
	void link_aFailedAttempt_doesNotBurnTheCode() {
		// Unlike an auth nonce, a link code must survive a malformed submission: the code is typed by a
		// person and a typo in the label or key should not force a new one.
		Principal owner = this.owner();
		DeviceLinkService.LinkCode code = this.links.mint(owner);

		assertThrows(ApiException.class, () -> this.links.link(code.code(), new byte[64], KeyAlgorithm.ED25519, "Junk"));

		assertDoesNotThrow(() ->
			this.links.link(code.code(), TestKeys.ecdsa("tablet").publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet"));
	}

	@Test
	void list_afterLinking_showsBothDevicesAndMarksTheCurrentOne() {
		Principal owner = this.owner();
		DeviceLinkService.LinkCode code = this.links.mint(owner);
		DeviceLinkService.Linked linked =
			this.links.link(code.code(), TestKeys.ecdsa("tablet").publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet");

		List<Device> all = this.links.list(owner);

		assertAll(
			() -> assertEquals(2, all.size()),
			() -> assertTrue(all.stream().anyMatch(device -> device.id().equals(owner.deviceId()))),
			() -> assertTrue(all.stream().anyMatch(device -> device.id().equals(linked.device().id())))
		);
	}

	@Test
	void list_showsOnlyTheCallersOwnDevices() {
		Principal owner = this.owner();
		Principal other = this.member(owner, "Other");

		List<Device> theirs = this.links.list(other);

		assertAll(
			() -> assertEquals(1, theirs.size()),
			() -> assertEquals(other.deviceId(), theirs.getFirst().id())
		);
	}
}
