package net.luis.sudoku.match;

import net.luis.sudoku.auth.*;
import net.luis.sudoku.config.*;
import net.luis.sudoku.currency.CurrencyService;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.dto.request.CreateMatchRequest;
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
	
	/**
	 * Multiplayer-game item 1: hints are a match setting now, chosen once by the creator and persisted with
	 * the rest of the configuration. It used to be an in-game switch on the co-op screen, which two players
	 * sharing one board could disagree about.
	 */
	@Test
	void create_withHintsDisabled_persistsTheSetting() {
		Principal owner = this.player("Owner");
		
		MatchService.Created created = this.matches.create(owner, MatchMode.COOP, GridSize.FOUR, Variant.CLASSIC,
			Difficulty.TWO, false, false, 0);
		
		assertFalse(created.match().hintsEnabled());
	}
	
	/**
	 * The opposite default from lives, and deliberately so: a client that says nothing about hints is asking
	 * for the ordinary game, and an older client cannot say anything at all.
	 */
	@Test
	void settings_omittingHints_defaultsToEnabled() {
		assertAll(
			() -> assertTrue(new CreateMatchRequest.Settings(null, null, null).hintsEnabledOrDefault()),
			() -> assertFalse(new CreateMatchRequest.Settings(null, false, null).hintsEnabledOrDefault()),
			() -> assertFalse(new CreateMatchRequest.Settings(null, null, null).livesEnabledOrDefault())
		);
	}
	
	private MatchService.Created createRace(Principal creator, int stake) {
		return this.matches.create(creator, MatchMode.RACE, GridSize.FOUR, Variant.CLASSIC, Difficulty.TWO, false, true, stake);
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
			GridSize.NINE, Variant.CLASSIC, Difficulty.LISA, false, true, 0));
		
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
	void create_theInviteToken_isATypableMatchCode() {
		// The whole reason the token stopped being a session token: a player reads this one out loud.
		Principal owner = this.player("Owner");
		
		String code = this.createRace(owner, 0).inviteToken();
		
		assertAll(
			() -> assertEquals(9, code.length()),
			() -> assertEquals('-', code.charAt(4)),
			() -> assertTrue(code.matches("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}"), code + " is not Crockford Base32"),
			() -> assertEquals(code, CodeGenerator.canonicalMatchCode(code), "already canonical")
		);
	}
	
	@Test
	void create_twoMatches_getDifferentCodes() {
		Principal owner = this.player("Owner");
		
		assertNotEquals(this.createRace(owner, 0).inviteToken(), this.createRace(owner, 0).inviteToken());
	}
	
	@Test
	void joinByCode_withTheCode_addsTheParticipantWithoutAMatchId() {
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		MatchService.Created created = this.createRace(owner, 0);
		
		Match joined = this.matches.joinByCode(guest, created.inviteToken());
		
		assertAll(
			() -> assertEquals(created.match().id(), joined.id(), "the id the caller never had comes back"),
			() -> assertEquals(2, this.matches.participants(created.match().id()).size())
		);
	}
	
	@Test
	void joinByCode_ignoresCasingAndGrouping() {
		// A code arrives by whatever means people talk to each other, so it arrives mistyped in these ways.
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		MatchService.Created created = this.createRace(owner, 0);
		String typed = created.inviteToken().replace("-", "").toLowerCase(Locale.ROOT);
		
		assertDoesNotThrow(() -> this.matches.joinByCode(guest, " " + typed + " "));
		assertEquals(2, this.matches.participants(created.match().id()).size());
	}
	
	@Test
	void joinByCode_withAnUnknownCode_isNotFound() {
		Principal guest = this.player("Guest");
		
		ApiException e = assertThrows(ApiException.class, () -> this.matches.joinByCode(guest, "ZZZZ-ZZZZ"));
		assertEquals(ErrorCode.NOT_FOUND, e.code());
	}
	
	@Test
	void joinByCode_withAMalformedCode_isNotFoundRatherThanBadRequest() {
		// Same answer as a code that simply does not exist: telling a guesser which of their attempts was
		// even the right shape is telling them something.
		Principal guest = this.player("Guest");
		
		assertAll(
			() -> assertEquals(ErrorCode.NOT_FOUND, assertThrows(ApiException.class, () -> this.matches.joinByCode(guest, "")).code()),
			() -> assertEquals(ErrorCode.NOT_FOUND, assertThrows(ApiException.class, () -> this.matches.joinByCode(guest, "TOOSHORT1234")).code()),
			() -> assertEquals(ErrorCode.NOT_FOUND, assertThrows(ApiException.class, () -> this.matches.joinByCode(guest, "IIII-LLLL")).code())
		);
	}
	
	@Test
	void joinByCode_afterTheMatchStarted_isNotFound() {
		// A code only opens a lobby. Once the match is running it stops resolving at all, which is what keeps
		// eight characters an acceptable length.
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		MatchService.Created created = this.createRace(owner, 0);
		database.execute(connection -> this.matchRepository.markRunning(connection, created.match().id(), NOW));
		
		ApiException e = assertThrows(ApiException.class, () -> this.matches.joinByCode(guest, created.inviteToken()));
		assertEquals(ErrorCode.NOT_FOUND, e.code());
	}
	
	@Test
	void joinByCode_afterTheMatchWasCancelled_isNotFound() {
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		MatchService.Created created = this.createRace(owner, 0);
		this.matches.cancel(owner, created.match().id());
		
		ApiException e = assertThrows(ApiException.class, () -> this.matches.joinByCode(guest, created.inviteToken()));
		assertEquals(ErrorCode.NOT_FOUND, e.code());
	}
	
	@Test
	void joinByCode_reusesACodeOnceItsMatchIsOver() {
		// Codes are unique among lobbies, not across history: a finished match keeps its code on the row and
		// the space is recycled rather than exhausted.
		Principal owner = this.player("Owner");
		MatchService.Created first = this.createRace(owner, 0);
		this.matches.cancel(owner, first.match().id());
		
		Match stored = this.matches.get(first.match().id());
		assertEquals(first.inviteToken(), stored.inviteToken(), "the code stays on the row for the record");
	}
	
	@Test
	void joinByCode_whenFull_isRejectedWithMatchFull() {
		// The code resolves; capacity is still capacity, and the reason must say so rather than "no such match".
		Principal owner = this.player("Owner");
		MatchService.Created created = this.createRace(owner, 0);
		this.matches.joinByCode(this.player("Guest"), created.inviteToken());
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.matches.joinByCode(this.player("Third"), created.inviteToken()));
		assertEquals(ErrorCode.MATCH_FULL, e.code());
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
			Difficulty.TWO, false, true, 0);
		
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
	void cancel_byTheCreator_abandonsTheMatchAndDropsItFromTheRegistry() {
		Principal owner = this.player("Owner");
		MatchService.Created created = this.createRace(owner, 0);
		
		this.matches.cancel(owner, created.match().id());
		
		Match stored = this.matches.get(created.match().id());
		assertAll(
			() -> assertEquals(MatchState.ABANDONED, stored.state()),
			() -> assertEquals(EndReason.CANCELLED, stored.endReason()),
			() -> assertNull(stored.winnerId()),
			() -> assertNull(this.registry.find(created.match().id()), "the live match is gone too")
		);
	}
	
	@Test
	void cancel_thenJoin_isRejected() {
		// The whole point of cancelling: a token that was already shared must stop working.
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		MatchService.Created created = this.createRace(owner, 0);
		this.matches.cancel(owner, created.match().id());
		
		ApiException e = assertThrows(ApiException.class,
			() -> this.matches.join(guest, created.match().id(), created.inviteToken()));
		assertEquals(ErrorCode.CONFLICT, e.code());
	}
	
	@Test
	void cancel_bySomebodyElse_isForbidden() {
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		MatchService.Created created = this.createRace(owner, 0);
		this.matches.join(guest, created.match().id(), created.inviteToken());
		
		ApiException e = assertThrows(ApiException.class, () -> this.matches.cancel(guest, created.match().id()));
		
		assertAll(
			() -> assertEquals(ErrorCode.FORBIDDEN, e.code(), "a participant is not the creator"),
			() -> assertEquals(MatchState.WAITING, this.matches.get(created.match().id()).state())
		);
	}
	
	@Test
	void cancel_aRunningMatch_isRejectedWithConflict() {
		// Leaving a running match is resigning, which the socket handles and which produces a result.
		Principal owner = this.player("Owner");
		MatchService.Created created = this.createRace(owner, 0);
		database.execute(connection -> this.matchRepository.markRunning(connection, created.match().id(), NOW));
		
		ApiException e = assertThrows(ApiException.class, () -> this.matches.cancel(owner, created.match().id()));
		
		assertAll(
			() -> assertEquals(ErrorCode.CONFLICT, e.code()),
			() -> assertEquals(409, e.status()),
			() -> assertEquals(MatchState.RUNNING, this.matches.get(created.match().id()).state())
		);
	}
	
	@Test
	void cancel_twice_isIdempotent() {
		// A client that is unsure its cancel landed must not be handed a failure for having succeeded.
		Principal owner = this.player("Owner");
		MatchService.Created created = this.createRace(owner, 0);
		this.matches.cancel(owner, created.match().id());
		
		assertDoesNotThrow(() -> this.matches.cancel(owner, created.match().id()));
		assertEquals(EndReason.CANCELLED, this.matches.get(created.match().id()).endReason());
	}
	
	@Test
	void cancel_anUnknownMatch_isNotFound() {
		Principal owner = this.player("Owner");
		ApiException e = assertThrows(ApiException.class, () -> this.matches.cancel(owner, UUID.randomUUID()));
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
	
	/**
	 * The startup question a client asks after being killed mid-match: which match am I still in? Nothing on
	 * the device survives the process to answer it.
	 */
	@Test
	void activeMatch_whileRunning_isTheMatchThisPlayerIsIn() {
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		Principal stranger = this.player("Stranger");
		MatchService.Created created = this.createRace(owner, 0);
		this.matches.join(guest, created.match().id(), created.inviteToken());
		database.execute(connection -> this.matchRepository.markRunning(connection, created.match().id(), NOW));
		
		Match active = this.matches.activeMatch(owner);
		assertAll(
			() -> assertNotNull(active),
			() -> assertEquals(created.match().id(), active.id()),
			() -> assertEquals(created.match().id(), this.matches.activeMatch(guest).id(), "both sides are in it"),
			() -> assertNull(this.matches.activeMatch(stranger), "and nobody else is")
		);
	}
	
	/**
	 * A lobby is not something a player has to be asked about: they can walk back into it from the
	 * multiplayer screen, and nothing is escrowed yet.
	 */
	@Test
	void activeMatch_aWaitingMatch_isNotOne() {
		Principal owner = this.player("Owner");
		this.createRace(owner, 0);
		
		assertNull(this.matches.activeMatch(owner));
	}
	
	@Test
	void activeMatch_afterItEnded_isNothing() {
		Principal owner = this.player("Owner");
		MatchService.Created created = this.createRace(owner, 0);
		database.execute(connection -> this.matchRepository.markRunning(connection, created.match().id(), NOW));
		// The registry's copy is the one a real running match would have transitioned itself; this test drives
		// the row directly, so the live object is removed and the persisted path is what runs.
		this.registry.remove(created.match().id());
		this.matches.resign(owner, created.match().id());
		
		assertNull(this.matches.activeMatch(owner));
	}
	
	/**
	 * Declining to rejoin ends the match there and then. The point of the whole call: the other players are
	 * sitting at a paused board waiting out a grace window this player has already decided not to use.
	 */
	@Test
	void resign_aRunningMatch_endsItAndRefundsTheStakes() {
		Principal owner = this.player("Owner");
		Principal guest = this.player("Guest");
		this.fund(owner, 100);
		this.fund(guest, 100);
		MatchService.Created created = this.createRace(owner, 30);
		this.matches.join(guest, created.match().id(), created.inviteToken());
		database.execute(connection -> {
			this.matchRepository.markRunning(connection, created.match().id(), NOW);
			this.currency.escrowStake(connection, owner.userId(), 30, created.match().id());
			this.currency.escrowStake(connection, guest.userId(), 30, created.match().id());
		});
		// No live object: the registry lost it, which is exactly the state a restart leaves behind.
		this.registry.remove(created.match().id());
		
		this.matches.resign(owner, created.match().id());
		
		Match after = this.matches.get(created.match().id());
		assertAll(
			() -> assertEquals(MatchState.ABANDONED, after.state()),
			() -> assertEquals(EndReason.RESIGNED, after.endReason()),
			() -> assertEquals(100, this.currency.balance(owner.userId()), "nobody won, so both stakes come back"),
			() -> assertEquals(100, this.currency.balance(guest.userId()))
		);
	}
	
	@Test
	void resign_byAPlayerWhoIsNotInIt_isForbidden() {
		Principal owner = this.player("Owner");
		Principal stranger = this.player("Stranger");
		MatchService.Created created = this.createRace(owner, 0);
		database.execute(connection -> this.matchRepository.markRunning(connection, created.match().id(), NOW));
		
		ApiException thrown = assertThrows(ApiException.class, () -> this.matches.resign(stranger, created.match().id()));
		assertEquals(ErrorCode.FORBIDDEN, thrown.code());
	}
	
	@Test
	void resign_aMatchThatAlreadyEnded_isAccepted() {
		Principal owner = this.player("Owner");
		MatchService.Created created = this.createRace(owner, 0);
		database.execute(connection -> this.matchRepository.markRunning(connection, created.match().id(), NOW));
		this.registry.remove(created.match().id());
		this.matches.resign(owner, created.match().id());
		
		// Idempotent: the answer to a retry after an uncertain failure must not be an error.
		assertDoesNotThrow(() -> this.matches.resign(owner, created.match().id()));
		assertEquals(EndReason.RESIGNED, this.matches.get(created.match().id()).endReason());
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
	void liveFor_aMatchThatAlreadyEnded_isRefused() {
		Principal owner = this.player("Owner");
		MatchService.Created created = this.createRace(owner, 0);
		this.matches.cancel(owner, created.match().id());
		
		Match ended = this.matches.get(created.match().id());
		
		// Rebuilding one would hand a returning player an empty board that has forgotten it ever ended, which
		// is what left a player who closed the app mid-match staring at a match nothing could end.
		assertAll(
			() -> assertTrue(ended.state().isTerminal()),
			() -> assertThrows(IllegalStateException.class, () -> this.matches.liveFor(ended)),
			() -> assertNull(this.registry.find(created.match().id()))
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
