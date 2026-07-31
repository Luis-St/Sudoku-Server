package net.luis.sudoku.match;

import net.luis.sudoku.auth.*;
import net.luis.sudoku.config.*;
import net.luis.sudoku.currency.CurrencyService;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.puzzle.PuzzleQueue;
import net.luis.sudoku.repository.*;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.sudoku.support.PostgresTest;
import net.luis.sudoku.support.TestKeys;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link MatchService}, covering server-spec 10.1 and the stake handling in 9a.3.
 */
class MatchServiceTest extends PostgresTest {
	
	private static final String BOOTSTRAP = "BOOTSTRAP1";
	private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
	
	private InviteRepository invites;
	private MatchRepository matchRepository;
	private CurrencyLedgerRepository ledger;
	private RegistrationService registrations;
	private CurrencyService currency;
	private MatchRegistry registry;
	private PuzzleQueue puzzles;
	private MatchService matches;
	
	@BeforeEach
	void createServices() {
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		
		Map<String, String> env = new HashMap<>();
		env.put(EnvKeys.DB_URL, "jdbc:postgresql://db:5432/sudoku");
		env.put(EnvKeys.DB_USER, "sudoku");
		env.put(EnvKeys.DB_PASSWORD, "secret");
		env.put(EnvKeys.BOOTSTRAP_INVITE, BOOTSTRAP);
		ServerConfig config = ServerConfig.from(Env.of(env));
		
		this.invites = new InviteRepository();
		this.matchRepository = new MatchRepository();
		this.ledger = new CurrencyLedgerRepository();
		UserRepository users = new UserRepository();
		DeviceRepository devices = new DeviceRepository();
		StatsRepository stats = new StatsRepository();
		
		SessionService sessionService = new SessionService(database, new SessionRepository(), users, devices,
			new CodeGenerator(), SessionCloser.NONE);
		this.registrations = new RegistrationService(database, users, devices, this.invites, sessionService,
			new SignatureVerifier(), clock);
		this.currency = new CurrencyService(database, this.ledger, stats, config, clock);
		this.registry = new MatchRegistry();
		this.puzzles = new PuzzleQueue(() -> 0);
		this.matches = new MatchService(database, this.matchRepository, this.registry, this.puzzles, this.currency,
			config, new CodeGenerator(), clock);
		this.registrations.ensureBootstrapInvite(BOOTSTRAP);
	}
	
	@AfterEach
	void shutdown() {
		this.registry.close();
		this.puzzles.close();
	}
	
	private Principal player(String name) {
		String code = BOOTSTRAP;
		if (!"Owner".equals(name)) {
			code = "INV" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
			String finalCode = code;
			database.execute(connection -> this.invites.create(connection, finalCode, null, Role.NEW, null, NOW));
		}
		TestKeys keys = TestKeys.ed25519(name);
		RegistrationService.Registered registered =
			this.registrations.register(code, name, keys.publicKey(), keys.algorithm(), "Phone");
		return new Principal(registered.user(), registered.device(), registered.session());
	}
	
	private void fund(Principal player, int amount) {
		database.execute(connection ->
			this.ledger.append(connection, player.userId(), amount, net.luis.sudoku.currency.LedgerReason.EARN_GAME, null, NOW));
	}
	
	private MatchService.Created createRace(Principal creator, int stake) {
		return this.matches.create(creator, MatchMode.RACE, GridSize.FOUR, Variant.CLASSIC, Difficulty.TWO, false, stake);
	}
	
	@Test
	void create_aRace_persistsItAndRegistersALiveMatch() {
		Principal owner = this.player("Owner");
		
		MatchService.Created created = this.createRace(owner, 0);
		
		assertAll(
			() -> assertEquals(MatchMode.RACE, created.match().mode()),
			() -> assertEquals(MatchState.WAITING, created.match().state()),
			() -> assertFalse(created.inviteToken().isBlank()),
			() -> assertNotNull(this.registry.find(created.match().id())),
			() -> assertEquals(1, this.matches.participants(created.match().id()).size(), "the creator joins")
		);
	}
	
	@Test
	void create_withLisa_isRejected() {
		// Spec 10.1: Lisa carries gameplay modifiers and is single-player only.
		Principal owner = this.player("Owner");
		
		ApiException e = assertThrows(ApiException.class, () -> this.matches.create(owner, MatchMode.DUEL,
			GridSize.NINE, Variant.CLASSIC, Difficulty.LISA, false, 0));
		
		assertAll(
			() -> assertEquals(ErrorCode.LISA_NOT_ALLOWED, e.code()),
			() -> assertEquals(400, e.status())
		);
	}
	
	@Test
	void create_withANegativeStake_isRejected() {
		Principal owner = this.player("Owner");
		assertThrows(ApiException.class, () -> this.createRace(owner, -5));
	}
	
	@Test
	void create_theKeyRoundTrips() {
		Principal owner = this.player("Owner");
		MatchService.Created created = this.createRace(owner, 0);
		
		Match stored = this.matches.get(created.match().id());
		assertAll(
			() -> assertEquals(GridSize.FOUR, stored.key().size()),
			() -> assertEquals(Variant.CLASSIC, stored.key().variant()),
			() -> assertEquals(Difficulty.TWO, stored.key().difficulty()),
			() -> assertEquals(created.match().seed(), stored.key().seed())
		);
	}
	
	@Test
	void join_withTheCorrectToken_addsTheParticipant() {
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		MatchService.Created created = this.createRace(owner, 0);
		
		this.matches.join(guest, created.match().id(), created.inviteToken());
		
		assertEquals(2, this.matches.participants(created.match().id()).size());
	}
	
	@Test
	void join_withTheWrongToken_isForbidden() {
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		MatchService.Created created = this.createRace(owner, 0);
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.matches.join(guest, created.match().id(), "not-the-token"));
		assertEquals(ErrorCode.FORBIDDEN, e.code());
	}
	
	@Test
	void join_beyondCapacity_isRejectedWithMatchFull() {
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		Principal third = this.player("Third");
		MatchService.Created created = this.createRace(owner, 0);
		this.matches.join(guest, created.match().id(), created.inviteToken());
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.matches.join(third, created.match().id(), created.inviteToken()));
		
		assertAll(
			() -> assertEquals(ErrorCode.MATCH_FULL, e.code()),
			() -> assertEquals(409, e.status())
		);
	}
	
	@Test
	void join_aCoopMatch_admitsFour() {
		Principal owner = this.player("Owner");
		MatchService.Created created = this.matches.create(owner, MatchMode.COOP, GridSize.FOUR, Variant.CLASSIC,
			Difficulty.TWO, false, 0);
		
		for (String name : new String[] { "Second", "Third", "Fourth" }) {
			this.matches.join(this.player(name), created.match().id(), created.inviteToken());
		}
		
		assertEquals(4, this.matches.participants(created.match().id()).size());
	}
	
	@Test
	void join_belowTheStake_isRejectedWithInsufficientBalance() {
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		this.fund(owner, 100);
		MatchService.Created created = this.createRace(owner, 50);
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.matches.join(guest, created.match().id(), created.inviteToken()));
		
		assertAll(
			() -> assertEquals(ErrorCode.INSUFFICIENT_BALANCE, e.code()),
			() -> assertEquals(409, e.status())
		);
	}
	
	@Test
	void join_withAStakeOfZero_isAllowedWithAnEmptyBalance() {
		// Spec 9a.3: stake 0 means anyone may join, including a player with nothing.
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		MatchService.Created created = this.createRace(owner, 0);
		
		assertDoesNotThrow(() -> this.matches.join(guest, created.match().id(), created.inviteToken()));
	}
	
	@Test
	void join_twice_isIdempotent() {
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		MatchService.Created created = this.createRace(owner, 0);
		
		this.matches.join(guest, created.match().id(), created.inviteToken());
		this.matches.join(guest, created.match().id(), created.inviteToken());
		
		assertEquals(2, this.matches.participants(created.match().id()).size());
	}
	
	@Test
	void join_anUnknownMatch_isNotFound() {
		Principal guest = this.player("Owner");
		ApiException e = assertThrows(ApiException.class, () -> this.matches.join(guest, UUID.randomUUID(), "token"));
		assertEquals(ErrorCode.NOT_FOUND, e.code());
	}
	
	@Test
	void isParticipant_reflectsMembership() {
		Principal owner = this.player("Owner");
		Principal stranger = this.player("Stranger");
		MatchService.Created created = this.createRace(owner, 0);
		
		assertAll(
			() -> assertTrue(this.matches.isParticipant(created.match().id(), owner.userId())),
			() -> assertFalse(this.matches.isParticipant(created.match().id(), stranger.userId()))
		);
	}
	
	@Test
	void recoverAfterRestart_abandonsUnfinishedMatchesAndRefundsStakes() {
		// Live board state is memory-resident, so a restart can only abandon (spec 9a.3).
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		this.fund(owner, 100);
		this.fund(guest, 100);
		MatchService.Created created = this.createRace(owner, 30);
		this.matches.join(guest, created.match().id(), created.inviteToken());
		
		// Simulate a match that had started and escrowed.
		database.execute(connection -> {
			this.matchRepository.markRunning(connection, created.match().id(), NOW);
			this.currency.escrowStake(connection, owner.userId(), 30, created.match().id());
			this.currency.escrowStake(connection, guest.userId(), 30, created.match().id());
		});
		assertEquals(70, this.currency.balance(owner.userId()));
		
		int recovered = this.matches.recoverAfterRestart();
		
		Match after = this.matches.get(created.match().id());
		assertAll(
			() -> assertEquals(1, recovered),
			() -> assertEquals(MatchState.ABANDONED, after.state()),
			() -> assertEquals(EndReason.SERVER_RESTART, after.endReason()),
			() -> assertEquals(100, this.currency.balance(owner.userId()), "the stake came back"),
			() -> assertEquals(100, this.currency.balance(guest.userId()))
		);
	}
	
	@Test
	void recoverAfterRestart_aWaitingMatch_isAbandonedWithoutRefunds() {
		// Nothing was escrowed before RUNNING, so there is nothing to give back.
		Principal owner = this.player("Owner");
		this.fund(owner, 100);
		MatchService.Created created = this.createRace(owner, 30);
		
		this.matches.recoverAfterRestart();
		
		assertAll(
			() -> assertEquals(MatchState.ABANDONED, this.matches.get(created.match().id()).state()),
			() -> assertEquals(100, this.currency.balance(owner.userId()))
		);
	}
	
	@Test
	void recoverAfterRestart_withNothingUnfinished_doesNothing() {
		this.player("Owner");
		assertEquals(0, this.matches.recoverAfterRestart());
	}
	
	@Test
	void liveFor_aMatchTheRegistryHasLost_rebuildsIt() {
		Principal owner = this.player("Owner");
		MatchService.Created created = this.createRace(owner, 0);
		this.registry.remove(created.match().id());
		
		LiveMatch rebuilt = this.matches.liveFor(this.matches.get(created.match().id()));
		
		assertAll(
			() -> assertNotNull(rebuilt),
			() -> assertEquals(created.match().id(), rebuilt.id()),
			() -> assertNotNull(this.registry.find(created.match().id()))
		);
	}
	
	@Test
	void registry_activeCount_tracksLiveMatches() {
		Principal owner = this.player("Owner");
		assertEquals(0, this.registry.activeCount());
		
		this.createRace(owner, 0);
		
		assertEquals(1, this.registry.activeCount());
	}
}
