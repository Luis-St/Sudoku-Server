package net.luis.sudoku.permission;

import net.luis.sudoku.auth.*;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.invite.RegistrationService;
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
 * Test class for {@link UserAdminService}, covering the admin invariant (server-spec 7.1) and kick
 * semantics (7.2).
 */
class UserAdminServiceTest extends PostgresTest {
	
	private static final String BOOTSTRAP = "BOOTSTRAP1";
	private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
	private final List<String> closedFor = Collections.synchronizedList(new ArrayList<>());
	private UserRepository users;
	private DeviceRepository devices;
	private SessionRepository sessions;
	private InviteRepository invites;
	private RegistrationService registrations;
	private SessionService sessionService;
	private UserAdminService admin;
	
	@BeforeEach
	void createServices() {
		this.closedFor.clear();
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		
		this.users = new UserRepository();
		this.devices = new DeviceRepository();
		this.sessions = new SessionRepository();
		this.invites = new InviteRepository();
		
		SessionCloser closer = (userId, reason) -> this.closedFor.add(userId + ":" + reason);
		this.sessionService = new SessionService(database, this.sessions, this.users, this.devices,
			new CodeGenerator(), closer);
		this.registrations = new RegistrationService(database, this.users, this.devices, this.invites,
			this.sessionService, new SignatureVerifier(), clock);
		this.admin = new UserAdminService(database, this.users, this.devices, this.sessions, closer);
		this.registrations.ensureBootstrapInvite(BOOTSTRAP);
	}
	
	private Principal admin(String name) {
		TestKeys keys = TestKeys.ed25519(name);
		RegistrationService.Registered registered =
			this.registrations.register(BOOTSTRAP, name, keys.publicKey(), keys.algorithm(), name + " device");
		return new Principal(registered.user(), registered.device(), registered.session());
	}
	
	private Principal member(Principal inviter, String name, Role role) {
		String code = "INV" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		database.execute(connection -> this.invites.create(connection, code, inviter.userId(), Role.NEW, null, NOW));
		
		TestKeys keys = TestKeys.ed25519(name);
		RegistrationService.Registered registered =
			this.registrations.register(code, name, keys.publicKey(), keys.algorithm(), name + " device");
		if (role != Role.NEW) {
			database.execute(connection -> this.users.updateRole(connection, registered.user().id(), role));
		}
		User user = new User(registered.user().id(), registered.user().displayName(), role,
			registered.user().createdAt(), false, null, false);
		return new Principal(user, registered.device(), registered.session());
	}
	
	// --- permission table (spec 7) ---
	
	@Test
	void permissions_theRoleTable_matchesTheSpecification() {
		assertAll(
			() -> assertEquals(java.util.Set.of(Permission.CAN_PLAY), Role.NEW.permissions()),
			() -> assertEquals(java.util.Set.of(Permission.CAN_PLAY, Permission.CAN_INVITE), Role.MEMBER.permissions()),
			() -> assertEquals(java.util.Set.of(Permission.CAN_PLAY, Permission.CAN_INVITE, Permission.CAN_KICK,
				Permission.CAN_CHANGE_ROLE), Role.ADMIN.permissions())
		);
	}
	
	@Test
	void has_aRevokedUser_keepsNoPermissions() {
		// A kicked user keeps their role on paper, but must not be able to act on it.
		User revoked = new User(UUID.randomUUID(), "Gone", Role.ADMIN, NOW, true, null, false);
		assertAll(
			() -> assertFalse(revoked.has(Permission.CAN_PLAY)),
			() -> assertFalse(revoked.has(Permission.CAN_KICK))
		);
	}
	
	// --- role changes ---
	
	@Test
	void changeRole_byAnAdmin_updatesTheRole() {
		Principal owner = this.admin("Owner");
		Principal player = this.member(owner, "Player", Role.NEW);
		
		User updated = this.admin.changeRole(owner, player.userId(), Role.MEMBER);
		
		assertEquals(Role.MEMBER, updated.role());
		assertEquals(Role.MEMBER, this.admin.get(player.userId()).role());
	}
	
	@Test
	void changeRole_byANonAdmin_isForbidden() {
		Principal owner = this.admin("Owner");
		Principal member = this.member(owner, "Member", Role.MEMBER);
		Principal player = this.member(owner, "Player", Role.NEW);
		
		ApiException e = assertThrows(ApiException.class, () -> this.admin.changeRole(member, player.userId(), Role.MEMBER));
		assertAll(
			() -> assertEquals(ErrorCode.FORBIDDEN, e.code()),
			() -> assertEquals(403, e.status())
		);
	}
	
	@Test
	void changeRole_demotingTheOnlyAdmin_isRejectedWithLastAdmin() {
		Principal owner = this.admin("Owner");
		
		ApiException e = assertThrows(ApiException.class, () -> this.admin.changeRole(owner, owner.userId(), Role.MEMBER));
		assertAll(
			() -> assertEquals(ErrorCode.LAST_ADMIN, e.code()),
			() -> assertEquals(409, e.status()),
			() -> assertEquals(Role.ADMIN, this.admin.get(owner.userId()).role(), "the demotion must not have applied")
		);
	}
	
	@Test
	void changeRole_demotingAnAdminWhileAnotherRemains_isAllowed() {
		Principal owner = this.admin("Owner");
		Principal second = this.member(owner, "Second", Role.ADMIN);
		
		User updated = this.admin.changeRole(owner, second.userId(), Role.MEMBER);
		
		assertEquals(Role.MEMBER, updated.role());
	}
	
	@Test
	void changeRole_promotingToAdmin_thenDemotingTheOriginal_isAllowed() {
		// The documented way to hand over ownership: promote first, then step down.
		Principal owner = this.admin("Owner");
		Principal successor = this.member(owner, "Successor", Role.NEW);
		
		this.admin.changeRole(owner, successor.userId(), Role.ADMIN);
		User demoted = this.admin.changeRole(owner, owner.userId(), Role.MEMBER);
		
		assertEquals(Role.MEMBER, demoted.role());
	}
	
	@Test
	void changeRole_toTheSameRole_isANoOp() {
		Principal owner = this.admin("Owner");
		// Would otherwise trip the last-admin guard even though nothing is changing.
		assertEquals(Role.ADMIN, this.admin.changeRole(owner, owner.userId(), Role.ADMIN).role());
	}
	
	@Test
	void changeRole_anUnknownUser_isNotFound() {
		Principal owner = this.admin("Owner");
		ApiException e = assertThrows(ApiException.class,
			() -> this.admin.changeRole(owner, UUID.randomUUID(), Role.MEMBER));
		assertEquals(ErrorCode.NOT_FOUND, e.code());
	}
	
	@Test
	void changeRole_twoAdminsDemotingEachOtherAtOnce_leavesOneStanding() throws Exception {
		// SELECT ... FOR UPDATE in the same transaction as the mutation is what stops both from
		// observing "another admin exists" and both succeeding (spec 7.1).
		Principal first = this.admin("First");
		Principal second = this.member(first, "Second", Role.ADMIN);
		
		List<Callable<Boolean>> demotions = List.of(
			() -> this.demote(first, second.userId()),
			() -> this.demote(second, first.userId())
		);
		
		int won = 0;
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			for (Future<Boolean> result : executor.invokeAll(demotions)) {
				if (result.get()) {
					won++;
				}
			}
		}
		int succeeded = won;
		
		int admins = database.read(connection -> this.users.countActiveAdmins(connection, null));
		assertAll(
			() -> assertEquals(1, succeeded, "only one demotion should have applied"),
			() -> assertEquals(1, admins, "an admin must always remain")
		);
	}
	
	private boolean demote(Principal actor, UUID target) {
		try {
			this.admin.changeRole(actor, target, Role.MEMBER);
			return true;
		} catch (ApiException e) {
			assertEquals(ErrorCode.LAST_ADMIN, e.code());
			return false;
		}
	}
	
	// --- kicks ---
	
	@Test
	void kick_aMember_revokesTheUserEveryDeviceAndTheSession() {
		Principal owner = this.admin("Owner");
		Principal player = this.member(owner, "Player", Role.NEW);
		
		this.admin.kick(owner, player.userId());
		
		List<Device> playerDevices = database.read(connection -> this.devices.findByUser(connection, player.userId()));
		assertAll(
			() -> assertTrue(this.admin.get(player.userId()).revoked()),
			() -> assertFalse(playerDevices.isEmpty()),
			() -> assertTrue(playerDevices.stream().allMatch(Device::revoked), "every key must be revoked"),
			() -> assertNull(database.read(connection -> this.sessions.findByUser(connection, player.userId())))
		);
	}
	
	@Test
	void kick_aMember_closesTheirSockets() {
		Principal owner = this.admin("Owner");
		Principal player = this.member(owner, "Player", Role.NEW);
		
		this.admin.kick(owner, player.userId());
		
		assertEquals(List.of(player.userId() + ":" + ErrorCode.USER_REVOKED.name()), this.closedFor);
	}
	
	@Test
	void kick_aKickedUser_cannotAuthenticateWithAnyOldKey() {
		Principal owner = this.admin("Owner");
		Principal player = this.member(owner, "Player", Role.NEW);
		String token = player.session().token();
		
		this.admin.kick(owner, player.userId());
		
		assertThrows(ApiException.class, () -> this.sessionService.authenticate(token, NOW));
	}
	
	@Test
	void kick_retainsTheUserRowSoHistoricalResultsSurvive() {
		Principal owner = this.admin("Owner");
		Principal player = this.member(owner, "Player", Role.NEW);
		
		this.admin.kick(owner, player.userId());
		
		User stored = this.admin.get(player.userId());
		assertAll(
			() -> assertNotNull(stored),
			() -> assertEquals("Player", stored.displayName()),
			() -> assertTrue(stored.revoked())
		);
	}
	
	@Test
	void kick_byANonAdmin_isForbidden() {
		Principal owner = this.admin("Owner");
		Principal member = this.member(owner, "Member", Role.MEMBER);
		Principal player = this.member(owner, "Player", Role.NEW);
		
		ApiException e = assertThrows(ApiException.class, () -> this.admin.kick(member, player.userId()));
		assertEquals(ErrorCode.FORBIDDEN, e.code());
	}
	
	@Test
	void kick_yourself_isRejected() {
		Principal owner = this.admin("Owner");
		ApiException e = assertThrows(ApiException.class, () -> this.admin.kick(owner, owner.userId()));
		assertEquals(ErrorCode.BAD_REQUEST, e.code());
	}
	
	@Test
	void kick_theSoleAdminAttemptingToRemoveThemselves_isRefusedBeforeTheInvariantIsConsulted() {
		// A kick can never breach the admin invariant through the API: only ADMIN holds CAN_KICK, and
		// self-kick is refused outright, so a sole admin has no way to remove the last admin. The
		// LAST_ADMIN guard inside kick() is therefore defensive depth, not a reachable path - and this
		// test pins the reason down rather than pretending to exercise it.
		Principal owner = this.admin("Owner");
		
		ApiException e = assertThrows(ApiException.class, () -> this.admin.kick(owner, owner.userId()));
		
		assertAll(
			() -> assertEquals(ErrorCode.BAD_REQUEST, e.code()),
			() -> assertFalse(this.admin.get(owner.userId()).revoked())
		);
	}
	
	@Test
	void kick_anAdminWhileAnotherRemains_isAllowed() {
		Principal owner = this.admin("Owner");
		Principal second = this.member(owner, "Second", Role.ADMIN);
		
		this.admin.kick(owner, second.userId());
		
		int remainingAdmins = database.read(connection -> this.users.countActiveAdmins(connection, null));
		assertAll(
			() -> assertTrue(this.admin.get(second.userId()).revoked()),
			() -> assertEquals(1, remainingAdmins)
		);
	}
	
	@Test
	void kick_anAlreadyKickedUser_isIdempotent() {
		Principal owner = this.admin("Owner");
		Principal player = this.member(owner, "Player", Role.NEW);
		
		this.admin.kick(owner, player.userId());
		assertDoesNotThrow(() -> this.admin.kick(owner, player.userId()));
	}
	
	@Test
	void kick_anUnknownUser_isNotFound() {
		Principal owner = this.admin("Owner");
		ApiException e = assertThrows(ApiException.class, () -> this.admin.kick(owner, UUID.randomUUID()));
		assertEquals(ErrorCode.NOT_FOUND, e.code());
	}
	
	// --- device revocation ---
	
	@Test
	void revokeDevice_yourOwnSecondDevice_succeedsAndDoesNotEndYourSession() {
		Principal owner = this.admin("Owner");
		TestKeys tablet = TestKeys.ecdsa("owner-tablet");
		Device second = database.transaction(connection -> this.devices.create(connection, owner.userId(),
			tablet.publicKey(), KeyAlgorithm.ECDSA_P256, "Tablet", NOW));
		
		boolean endedOwnSession = this.admin.revokeDevice(owner, second.id());
		
		assertAll(
			() -> assertFalse(endedOwnSession),
			() -> assertTrue(database.read(connection -> this.devices.find(connection, second.id())).revoked())
		);
	}
	
	@Test
	void revokeDevice_theLastKeyOfTheLastAdmin_isRejectedWithLastAdmin() {
		Principal owner = this.admin("Owner");
		
		ApiException e = assertThrows(ApiException.class, () -> this.admin.revokeDevice(owner, owner.deviceId()));
		assertAll(
			() -> assertEquals(ErrorCode.LAST_ADMIN, e.code()),
			() -> assertFalse(database.read(connection -> this.devices.find(connection, owner.deviceId())).revoked())
		);
	}
	
	@Test
	void revokeDevice_theLastKeyOfAnAdminWhenAnotherAdminExists_isAllowed() {
		Principal owner = this.admin("Owner");
		Principal second = this.member(owner, "Second", Role.ADMIN);
		
		assertDoesNotThrow(() -> this.admin.revokeDevice(owner, second.deviceId()));
	}
	
	@Test
	void revokeDevice_ofAnotherUserWithoutKickPermission_isForbidden() {
		Principal owner = this.admin("Owner");
		Principal member = this.member(owner, "Member", Role.MEMBER);
		Principal player = this.member(owner, "Player", Role.NEW);
		
		ApiException e = assertThrows(ApiException.class, () -> this.admin.revokeDevice(member, player.deviceId()));
		assertEquals(ErrorCode.FORBIDDEN, e.code());
	}
	
	@Test
	void revokeDevice_yourOwnCurrentDevice_endsTheSession() {
		Principal owner = this.admin("Owner");
		Principal player = this.member(owner, "Player", Role.NEW);
		
		boolean endedOwnSession = this.admin.revokeDevice(player, player.deviceId());
		
		assertAll(
			() -> assertTrue(endedOwnSession),
			() -> assertNull(database.read(connection -> this.sessions.findByToken(connection, player.session().token()))),
			() -> assertEquals(List.of(player.userId() + ":DEVICE_REVOKED"), this.closedFor)
		);
	}
	
	@Test
	void revokeDevice_anUnknownDevice_isNotFound() {
		Principal owner = this.admin("Owner");
		ApiException e = assertThrows(ApiException.class, () -> this.admin.revokeDevice(owner, UUID.randomUUID()));
		assertEquals(ErrorCode.NOT_FOUND, e.code());
	}
}
