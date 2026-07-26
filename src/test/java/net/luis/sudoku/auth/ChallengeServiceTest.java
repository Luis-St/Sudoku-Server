package net.luis.sudoku.auth;

import net.luis.sudoku.domain.KeyAlgorithm;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.domain.Session;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.repository.AuthChallengeRepository;
import net.luis.sudoku.repository.DeviceRepository;
import net.luis.sudoku.repository.InviteRepository;
import net.luis.sudoku.repository.SessionRepository;
import net.luis.sudoku.repository.UserRepository;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.sudoku.support.PostgresTest;
import net.luis.sudoku.support.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * Test class for {@link ChallengeService}, covering the handshake in server-spec 6.1 and the
 * single-active-session rule in 6.2.
 */
class ChallengeServiceTest extends PostgresTest {

	private static final String BOOTSTRAP = "BOOTSTRAP1";
	private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");

	private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
	private final List<String> closedFor = java.util.Collections.synchronizedList(new ArrayList<>());

	private UserRepository users;
	private DeviceRepository devices;
	private InviteRepository invites;
	private SessionRepository sessions;
	private AuthChallengeRepository challengeRepository;
	private SessionService sessionService;
	private ChallengeService challenges;
	private RegistrationService registrations;

	@BeforeEach
	void createServices() {
		this.now.set(NOW);
		this.closedFor.clear();

		Clock clock = new Clock() {
			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return ChallengeServiceTest.this.now.get();
			}
		};

		this.users = new UserRepository();
		this.devices = new DeviceRepository();
		this.invites = new InviteRepository();
		this.sessions = new SessionRepository();
		this.challengeRepository = new AuthChallengeRepository();

		SignatureVerifier verifier = new SignatureVerifier();
		SessionCloser closer = (userId, reason) -> this.closedFor.add(userId + ":" + reason);
		this.sessionService = new SessionService(database, this.sessions, this.users, this.devices,
			new CodeGenerator(), closer);
		this.challenges = new ChallengeService(database, this.challengeRepository, this.devices, this.users,
			this.sessionService, verifier, new CodeGenerator(), clock);
		this.registrations = new RegistrationService(database, this.users, this.devices, this.invites,
			this.sessionService, verifier, clock);
		this.registrations.ensureBootstrapInvite(BOOTSTRAP);
	}

	private RegistrationService.Registered register(TestKeys keys, String name) {
		return this.registrations.register(BOOTSTRAP, name, keys.publicKey(), keys.algorithm(), name + " device");
	}

	private String inviteFrom(UUID creator) {
		String code = "INV" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		database.execute(connection -> this.invites.create(connection, code, creator, Role.NEW, null, this.now.get()));
		return code;
	}

	@Test
	void challenge_aRegisteredKey_returnsANonceWithTheExpectedTtl() {
		TestKeys keys = TestKeys.ed25519("device-a");
		this.register(keys, "Alice");

		ChallengeService.Challenge challenge = this.challenges.challenge(keys.publicKey());

		assertAll(
			() -> assertEquals(CodeGenerator.NONCE_BYTES, challenge.nonce().length),
			() -> assertEquals(NOW.plus(ChallengeService.CHALLENGE_TTL), challenge.expiresAt())
		);
	}

	@Test
	void challenge_anUnknownKey_isRejectedWithUnknownKey() {
		TestKeys stranger = TestKeys.ed25519("stranger");

		ApiException e = assertThrows(ApiException.class, () -> this.challenges.challenge(stranger.publicKey()));
		assertAll(
			() -> assertEquals(ErrorCode.UNKNOWN_KEY, e.code()),
			() -> assertEquals(404, e.status())
		);
	}

	@Test
	void challenge_aRevokedDevice_isRejected() {
		TestKeys keys = TestKeys.ed25519("device-a");
		RegistrationService.Registered registered = this.register(keys, "Alice");
		database.execute(connection -> this.devices.revoke(connection, registered.device().id()));

		ApiException e = assertThrows(ApiException.class, () -> this.challenges.challenge(keys.publicKey()));
		assertEquals(ErrorCode.USER_REVOKED, e.code());
	}

	@Test
	void challenge_aRevokedUser_isRejected() {
		TestKeys keys = TestKeys.ed25519("device-a");
		RegistrationService.Registered registered = this.register(keys, "Alice");
		database.execute(connection -> this.users.revoke(connection, registered.user().id()));

		ApiException e = assertThrows(ApiException.class, () -> this.challenges.challenge(keys.publicKey()));
		assertEquals(ErrorCode.USER_REVOKED, e.code());
	}

	@Test
	void challenge_requestedTwice_invalidatesTheEarlierNonce() {
		TestKeys keys = TestKeys.ed25519("device-a");
		this.register(keys, "Alice");

		ChallengeService.Challenge first = this.challenges.challenge(keys.publicKey());
		this.challenges.challenge(keys.publicKey());

		ApiException e = assertThrows(ApiException.class,
			() -> this.challenges.verify(first.nonce(), keys.sign(first.nonce())));
		assertEquals(ErrorCode.INVALID_SIGNATURE, e.code());
	}

	@Test
	void verify_aCorrectEd25519Signature_issuesASession() {
		TestKeys keys = TestKeys.ed25519("device-a");
		RegistrationService.Registered registered = this.register(keys, "Alice");
		ChallengeService.Challenge challenge = this.challenges.challenge(keys.publicKey());

		ChallengeService.Authenticated authenticated = this.challenges.verify(challenge.nonce(), keys.sign(challenge.nonce()));

		assertAll(
			() -> assertEquals(registered.user().id(), authenticated.user().id()),
			() -> assertEquals(registered.device().id(), authenticated.device().id()),
			() -> assertFalse(authenticated.session().token().isBlank()),
			() -> assertNotEquals(registered.session().token(), authenticated.session().token())
		);
	}

	@Test
	void verify_aCorrectEcdsaSignature_issuesASession() {
		// Android devices sign with ECDSA P-256, so this path must work as well as Ed25519.
		TestKeys keys = TestKeys.ecdsa("android");
		this.register(keys, "Android");
		ChallengeService.Challenge challenge = this.challenges.challenge(keys.publicKey());

		ChallengeService.Authenticated authenticated = this.challenges.verify(challenge.nonce(), keys.sign(challenge.nonce()));

		assertEquals(KeyAlgorithm.ECDSA_P256, authenticated.device().keyAlgorithm());
	}

	@Test
	void verify_aWrongSignature_isRejected() {
		TestKeys keys = TestKeys.ed25519("device-a");
		TestKeys impostor = TestKeys.ed25519("impostor");
		this.register(keys, "Alice");
		ChallengeService.Challenge challenge = this.challenges.challenge(keys.publicKey());

		ApiException e = assertThrows(ApiException.class,
			() -> this.challenges.verify(challenge.nonce(), impostor.sign(challenge.nonce())));
		assertAll(
			() -> assertEquals(ErrorCode.INVALID_SIGNATURE, e.code()),
			() -> assertEquals(401, e.status())
		);
	}

	@Test
	void verify_aSignatureOverDifferentBytes_isRejected() {
		TestKeys keys = TestKeys.ed25519("device-a");
		this.register(keys, "Alice");
		ChallengeService.Challenge challenge = this.challenges.challenge(keys.publicKey());

		ApiException e = assertThrows(ApiException.class,
			() -> this.challenges.verify(challenge.nonce(), keys.sign("something else".getBytes())));
		assertEquals(ErrorCode.INVALID_SIGNATURE, e.code());
	}

	@Test
	void verify_anUnknownNonce_isRejected() {
		TestKeys keys = TestKeys.ed25519("device-a");
		this.register(keys, "Alice");
		byte[] fabricated = new byte[CodeGenerator.NONCE_BYTES];

		ApiException e = assertThrows(ApiException.class,
			() -> this.challenges.verify(fabricated, keys.sign(fabricated)));
		assertEquals(ErrorCode.INVALID_SIGNATURE, e.code());
	}

	@Test
	void verify_aReplayedNonce_isRejectedTheSecondTime() {
		TestKeys keys = TestKeys.ed25519("device-a");
		this.register(keys, "Alice");
		ChallengeService.Challenge challenge = this.challenges.challenge(keys.publicKey());
		byte[] signature = keys.sign(challenge.nonce());

		this.challenges.verify(challenge.nonce(), signature);

		// Nonces are single-use and deleted on consumption (spec 12), so a captured pair is worthless.
		ApiException e = assertThrows(ApiException.class, () -> this.challenges.verify(challenge.nonce(), signature));
		assertEquals(ErrorCode.INVALID_SIGNATURE, e.code());
	}

	@Test
	void verify_anExpiredNonce_isRejected() {
		TestKeys keys = TestKeys.ed25519("device-a");
		this.register(keys, "Alice");
		ChallengeService.Challenge challenge = this.challenges.challenge(keys.publicKey());

		this.now.set(NOW.plus(ChallengeService.CHALLENGE_TTL).plusSeconds(1));

		ApiException e = assertThrows(ApiException.class,
			() -> this.challenges.verify(challenge.nonce(), keys.sign(challenge.nonce())));
		assertEquals(ErrorCode.INVALID_SIGNATURE, e.code());
	}

	@Test
	void verify_aFailedAttempt_burnsTheNonce() {
		TestKeys keys = TestKeys.ed25519("device-a");
		TestKeys impostor = TestKeys.ed25519("impostor");
		this.register(keys, "Alice");
		ChallengeService.Challenge challenge = this.challenges.challenge(keys.publicKey());

		assertThrows(ApiException.class, () -> this.challenges.verify(challenge.nonce(), impostor.sign(challenge.nonce())));

		// Even the rightful owner cannot reuse it: guessing costs a fresh round trip every time.
		ApiException e = assertThrows(ApiException.class,
			() -> this.challenges.verify(challenge.nonce(), keys.sign(challenge.nonce())));
		assertEquals(ErrorCode.INVALID_SIGNATURE, e.code());
	}

	@Test
	void verify_concurrentUseOfOneNonce_succeedsExactlyOnce() throws Exception {
		TestKeys keys = TestKeys.ed25519("device-a");
		this.register(keys, "Alice");
		ChallengeService.Challenge challenge = this.challenges.challenge(keys.publicKey());
		byte[] signature = keys.sign(challenge.nonce());

		int attempts = 6;
		List<Callable<Boolean>> uses = new ArrayList<>();
		for (int i = 0; i < attempts; i++) {
			uses.add(() -> {
				try {
					this.challenges.verify(challenge.nonce(), signature);
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

		// DELETE ... RETURNING is what makes this atomic; a read-then-delete would let several through.
		assertEquals(1, won, "exactly one verification should consume the nonce");
	}

	@Test
	void verify_aSecondLogin_displacesTheFirstSession() {
		TestKeys keys = TestKeys.ed25519("device-a");
		RegistrationService.Registered registered = this.register(keys, "Alice");
		String firstToken = registered.session().token();

		ChallengeService.Challenge challenge = this.challenges.challenge(keys.publicKey());
		ChallengeService.Authenticated second = this.challenges.verify(challenge.nonce(), keys.sign(challenge.nonce()));

		Session stored = database.read(connection -> this.sessions.findByUser(connection, registered.user().id()));
		assertAll(
			() -> assertNotNull(stored),
			() -> assertEquals(second.session().token(), stored.token()),
			() -> assertNotEquals(firstToken, stored.token()),
			() -> assertNull(database.read(connection -> this.sessions.findByToken(connection, firstToken)),
				"the displaced token must no longer resolve")
		);
	}

	@Test
	void verify_aSecondLogin_closesTheDisplacedClientsSockets() {
		TestKeys keys = TestKeys.ed25519("device-a");
		RegistrationService.Registered registered = this.register(keys, "Alice");

		ChallengeService.Challenge challenge = this.challenges.challenge(keys.publicKey());
		this.challenges.verify(challenge.nonce(), keys.sign(challenge.nonce()));

		assertEquals(List.of(registered.user().id() + ":" + SessionService.SUPERSEDED_REASON), this.closedFor);
	}

	@Test
	void verify_loggingInOnASecondDevice_displacesTheFirstDevicesSession() {
		// One session per user, not per device: signing in on a tablet signs the phone out (spec 6.2).
		TestKeys phone = TestKeys.ed25519("phone");
		RegistrationService.Registered registered = this.register(phone, "Alice");

		TestKeys tablet = TestKeys.ecdsa("tablet");
		database.execute(connection -> this.devices.create(connection, registered.user().id(), tablet.publicKey(),
			KeyAlgorithm.ECDSA_P256, "Tablet", this.now.get()));

		ChallengeService.Challenge challenge = this.challenges.challenge(tablet.publicKey());
		ChallengeService.Authenticated authenticated = this.challenges.verify(challenge.nonce(), tablet.sign(challenge.nonce()));

		assertAll(
			() -> assertEquals(registered.user().id(), authenticated.user().id()),
			() -> assertNull(database.read(connection -> this.sessions.findByToken(connection, registered.session().token())))
		);
	}

	@Test
	void authenticate_aValidToken_resolvesThePrincipal() {
		TestKeys keys = TestKeys.ed25519("device-a");
		RegistrationService.Registered registered = this.register(keys, "Alice");

		Principal principal = this.sessionService.authenticate(registered.session().token(), NOW);

		assertAll(
			() -> assertEquals(registered.user().id(), principal.userId()),
			() -> assertEquals(registered.device().id(), principal.deviceId()),
			() -> assertEquals(Role.ADMIN, principal.user().role())
		);
	}

	@Test
	void authenticate_anUnknownToken_isRejected() {
		ApiException e = assertThrows(ApiException.class, () -> this.sessionService.authenticate("nope", NOW));
		assertEquals(ErrorCode.UNAUTHORIZED, e.code());
	}

	@Test
	void authenticate_anExpiredToken_isRejected() {
		TestKeys keys = TestKeys.ed25519("device-a");
		RegistrationService.Registered registered = this.register(keys, "Alice");

		Instant afterExpiry = NOW.plus(SessionService.SESSION_TTL).plus(Duration.ofSeconds(1));
		ApiException e = assertThrows(ApiException.class,
			() -> this.sessionService.authenticate(registered.session().token(), afterExpiry));
		assertEquals(ErrorCode.UNAUTHORIZED, e.code());
	}

	@Test
	void authenticate_aKickedUser_isRejectedWithUserRevoked() {
		TestKeys keys = TestKeys.ed25519("device-a");
		RegistrationService.Registered registered = this.register(keys, "Alice");
		database.execute(connection -> this.users.revoke(connection, registered.user().id()));

		ApiException e = assertThrows(ApiException.class,
			() -> this.sessionService.authenticate(registered.session().token(), NOW));
		assertEquals(ErrorCode.USER_REVOKED, e.code());
	}

	@Test
	void authenticate_aRevokedDevice_isRejected() {
		TestKeys keys = TestKeys.ed25519("device-a");
		RegistrationService.Registered registered = this.register(keys, "Alice");
		database.execute(connection -> this.devices.revoke(connection, registered.device().id()));

		ApiException e = assertThrows(ApiException.class,
			() -> this.sessionService.authenticate(registered.session().token(), NOW));
		assertEquals(ErrorCode.UNAUTHORIZED, e.code());
	}

	@Test
	void endSession_afterSigningOut_theTokenNoLongerResolves() {
		TestKeys keys = TestKeys.ed25519("device-a");
		RegistrationService.Registered registered = this.register(keys, "Alice");

		this.sessionService.endSession(registered.session().token());

		assertThrows(ApiException.class, () -> this.sessionService.authenticate(registered.session().token(), NOW));
	}

	@Test
	void verify_afterRegisteringASecondUser_eachKeepsTheirOwnSession() {
		TestKeys alice = TestKeys.ed25519("alice");
		RegistrationService.Registered first = this.register(alice, "Alice");

		TestKeys bob = TestKeys.ed25519("bob");
		String code = this.inviteFrom(first.user().id());
		RegistrationService.Registered second =
			this.registrations.register(code, "Bob", bob.publicKey(), bob.algorithm(), "Bobs phone");

		assertAll(
			() -> assertNotNull(this.sessionService.authenticate(first.session().token(), NOW)),
			() -> assertNotNull(this.sessionService.authenticate(second.session().token(), NOW)),
			() -> assertNotEquals(first.session().token(), second.session().token())
		);
	}

	@Test
	void deviceLastSeen_afterAuthenticating_isUpdated() {
		TestKeys keys = TestKeys.ed25519("device-a");
		RegistrationService.Registered registered = this.register(keys, "Alice");

		this.now.set(NOW.plusSeconds(3600));
		ChallengeService.Challenge challenge = this.challenges.challenge(keys.publicKey());
		this.challenges.verify(challenge.nonce(), keys.sign(challenge.nonce()));

		var device = database.read(connection -> this.devices.find(connection, registered.device().id()));
		assertEquals(NOW.plusSeconds(3600), device.lastSeenAt());
	}
}
