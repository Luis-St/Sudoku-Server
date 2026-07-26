package net.luis.sudoku.daily;

import net.luis.sudoku.auth.SessionCloser;
import net.luis.sudoku.auth.SessionService;
import net.luis.sudoku.auth.SignatureVerifier;
import net.luis.sudoku.config.Env;
import net.luis.sudoku.config.EnvKeys;
import net.luis.sudoku.config.ServerConfig;
import net.luis.sudoku.currency.CurrencyService;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.DailyOutcome;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.key.PuzzleKey;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.puzzle.PuzzleFactory;
import net.luis.sudoku.repository.DailyLeaderboardRepository;
import net.luis.sudoku.repository.CurrencyLedgerRepository;
import net.luis.sudoku.repository.DailyResultRepository;
import net.luis.sudoku.repository.DeviceRepository;
import net.luis.sudoku.repository.InviteRepository;
import net.luis.sudoku.repository.PreferenceRepository;
import net.luis.sudoku.repository.SessionRepository;
import net.luis.sudoku.repository.StatsRepository;
import net.luis.sudoku.repository.StreakRepository;
import net.luis.sudoku.repository.UserRepository;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.sudoku.stats.StatsService;
import net.luis.sudoku.support.PostgresTest;
import net.luis.sudoku.support.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link DailyService}, covering server-spec 8.
 */
class DailyServiceTest extends PostgresTest {

	private static final String BOOTSTRAP = "BOOTSTRAP1";
	private static final String SERVER_ID = "0123456789abcdef0123456789abcdef";
	private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
	/** Noon Berlin time, comfortably away from any rollover boundary. */
	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

	private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

	private UserRepository users;
	private InviteRepository invites;
	private PreferenceRepository preferences;
	private DailyResultRepository results;
	private DailyLeaderboardRepository leaderboard;
	private RegistrationService registrations;
	private DailyService daily;
	private ServerConfig config;
	private CurrencyService currency;

	@BeforeEach
	void createServices() {
		this.now.set(NOW);
		Clock clock = new Clock() {
			@Override
			public ZoneId getZone() {
				return ZONE;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return DailyServiceTest.this.now.get();
			}
		};

		Map<String, String> env = new HashMap<>();
		env.put(EnvKeys.DB_URL, "jdbc:postgresql://db:5432/sudoku");
		env.put(EnvKeys.DB_USER, "sudoku");
		env.put(EnvKeys.DB_PASSWORD, "secret");
		env.put(EnvKeys.BOOTSTRAP_INVITE, BOOTSTRAP);
		env.put(EnvKeys.TIMEZONE, ZONE.getId());
		env.put(EnvKeys.DAILY_SIZE, "9");
		this.config = ServerConfig.from(Env.of(env));

		this.users = new UserRepository();
		this.invites = new InviteRepository();
		this.preferences = new PreferenceRepository();
		this.results = new DailyResultRepository();
		this.leaderboard = new DailyLeaderboardRepository();
		DeviceRepository devices = new DeviceRepository();
		StreakRepository streaks = new StreakRepository();

		SessionService sessionService = new SessionService(database, new SessionRepository(), this.users, devices,
			new CodeGenerator(), SessionCloser.NONE);
		this.registrations = new RegistrationService(database, this.users, devices, this.invites, sessionService,
			new SignatureVerifier(), clock);
		StatsRepository statsRepository = new StatsRepository();
		CurrencyService currency = new CurrencyService(database, new CurrencyLedgerRepository(), statsRepository,
			this.config, clock);
		StatsService statsService = new StatsService(database, statsRepository, this.users, streaks, this.results,
			this.leaderboard, this.config, clock);
		this.daily = new DailyService(database, this.config, SERVER_ID, this.preferences, this.results, streaks,
			this.leaderboard, currency, statsService, clock);
		this.currency = currency;
		this.registrations.ensureBootstrapInvite(BOOTSTRAP);
	}

	private Principal player(String name) {
		if (name.equals("Owner")) {
			TestKeys keys = TestKeys.ed25519(name);
			RegistrationService.Registered registered =
				this.registrations.register(BOOTSTRAP, name, keys.publicKey(), keys.algorithm(), "Phone");
			return new Principal(registered.user(), registered.device(), registered.session());
		}
		String code = "INV" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		database.execute(connection -> this.invites.create(connection, code, null, Role.NEW, null, this.now.get()));
		TestKeys keys = TestKeys.ed25519(name);
		RegistrationService.Registered registered =
			this.registrations.register(code, name, keys.publicKey(), keys.algorithm(), "Phone");
		return new Principal(registered.user(), registered.device(), registered.session());
	}

	/**
	 * @return the full, correct solve order for a puzzle, in cell-index order
	 */
	private static List<SolveVerifier.Entry> solveOrderFor(GeneratedPuzzle puzzle) {
		List<SolveVerifier.Entry> entries = new ArrayList<>();
		for (int index = 0; index < puzzle.puzzle().size().cellCount(); index++) {
			if (!puzzle.puzzle().cell(index).isGiven()) {
				entries.add(new SolveVerifier.Entry(index, puzzle.solutionAt(index)));
			}
		}
		return entries;
	}

	private DailyService.Submit solvedSubmission(LocalDate date, int difficulty) {
		GeneratedPuzzle puzzle = PuzzleFactory.generate(this.daily.keyFor(date, Difficulty.ofIndex(difficulty)));
		List<SolveVerifier.Entry> order = solveOrderFor(puzzle);
		long plausible = order.size() * SolveVerifier.MIN_MS_PER_CELL * 4;
		return new DailyService.Submit(date, difficulty, DailyOutcome.SOLVED, plausible, 0, 0, order);
	}

	// --- seed derivation and key issuance ---

	@Test
	void seedFor_theSameServerAndDate_isStable() {
		long first = SeedDerivation.seedFor(SERVER_ID, LocalDate.of(2026, 7, 25));
		long second = SeedDerivation.seedFor(SERVER_ID, LocalDate.of(2026, 7, 25));
		assertEquals(first, second);
	}

	@Test
	void seedFor_differentDatesOrServers_differ() {
		long today = SeedDerivation.seedFor(SERVER_ID, LocalDate.of(2026, 7, 25));
		long tomorrow = SeedDerivation.seedFor(SERVER_ID, LocalDate.of(2026, 7, 26));
		long elsewhere = SeedDerivation.seedFor("ffffffffffffffffffffffffffffffff", LocalDate.of(2026, 7, 25));

		assertAll(
			() -> assertNotEquals(today, tomorrow),
			() -> assertNotEquals(today, elsewhere)
		);
	}

	@Test
	void today_returnsAKeyMatchingTheConfiguredDailyShape() {
		Principal player = this.player("Owner");

		DailyService.Daily issued = this.daily.today(player);

		assertAll(
			() -> assertEquals(LocalDate.of(2026, 7, 25), issued.date()),
			() -> assertEquals(GridSize.NINE, issued.key().size()),
			() -> assertEquals(Variant.CLASSIC, issued.key().variant()),
			() -> assertEquals(Difficulty.THREE, issued.key().difficulty(), "default tier is 3"),
			() -> assertTrue(issued.key().isCurrentGenVersion())
		);
	}

	@Test
	void today_forTwoPlayersOnTheSameTier_yieldsTheSameKey() {
		// The daily is the same puzzle for everyone in a tier - that is what makes the leaderboard mean
		// anything.
		Principal first = this.player("Owner");
		Principal second = this.player("Second");

		assertEquals(this.daily.today(first).key(), this.daily.today(second).key());
	}

	@Test
	void today_differentTiers_yieldDifferentPuzzlesFromTheSameSeed() {
		PuzzleKey three = this.daily.keyFor(LocalDate.of(2026, 7, 25), Difficulty.THREE);
		PuzzleKey four = this.daily.keyFor(LocalDate.of(2026, 7, 25), Difficulty.FOUR);

		assertAll(
			() -> assertEquals(three.seed(), four.seed(), "difficulty is not part of the seed"),
			() -> assertNotEquals(three, four),
			() -> assertNotEquals(
				java.util.Arrays.toString(PuzzleFactory.generate(three).puzzle().values()),
				java.util.Arrays.toString(PuzzleFactory.generate(four).puzzle().values()),
				"but it does change the generated grid")
		);
	}

	// --- preferences ---

	@Test
	void preference_whenNeverSet_defaultsToThree() {
		Principal player = this.player("Owner");
		assertEquals(PreferenceRepository.DEFAULT_DIFFICULTY, this.daily.preference(player));
	}

	@Test
	void setPreference_takesEffectOnlyFromTheNextDay() {
		Principal player = this.player("Owner");
		// Locks today's tier at the default.
		PuzzleKey todaysKey = this.daily.today(player).key();

		this.daily.setPreference(player, 5);

		PuzzleKey stillTodays = this.daily.today(player).key();
		assertAll(
			() -> assertEquals(Difficulty.THREE, stillTodays.difficulty(), "today's tier must not change"),
			() -> assertEquals(todaysKey, stillTodays),
			() -> assertEquals(5, this.daily.preference(player), "but the standing preference is updated")
		);

		this.now.set(NOW.plus(java.time.Duration.ofDays(1)));
		assertEquals(Difficulty.FIVE, this.daily.today(player).key().difficulty(), "tomorrow uses the new tier");
	}

	@Test
	void setPreference_beforeTheFirstDailyRequestOfTheDay_appliesImmediately() {
		// Nothing has been locked in yet, so there is no mid-day switch to prevent.
		Principal player = this.player("Owner");
		this.daily.setPreference(player, 1);

		assertEquals(Difficulty.ONE, this.daily.today(player).key().difficulty());
	}

	@Test
	void setPreference_toLisa_isRejected() {
		Principal player = this.player("Owner");
		ApiException e = assertThrows(ApiException.class, () -> this.daily.setPreference(player, 6));
		assertEquals(ErrorCode.LISA_NOT_ALLOWED, e.code());
	}

	@Test
	void setPreference_outOfRange_isRejected() {
		Principal player = this.player("Owner");
		assertAll(
			() -> assertThrows(ApiException.class, () -> this.daily.setPreference(player, 0)),
			() -> assertThrows(ApiException.class, () -> this.daily.setPreference(player, 7))
		);
	}

	// --- result submission ---

	@Test
	void submit_aCorrectSolve_isAcceptedAndVerified() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();

		DailyService.Submission submission = this.daily.submit(player, this.solvedSubmission(date, 3));

		assertAll(
			() -> assertTrue(submission.accepted()),
			() -> assertTrue(submission.verified()),
			() -> assertEquals(1, submission.result().attemptNo()),
			() -> assertEquals(DailyOutcome.SOLVED, submission.result().outcome()),
			() -> assertEquals(1, submission.streak().current())
		);
	}

	@Test
	void submit_aSecondAttemptAfterSolving_isRejected() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();
		this.daily.submit(player, this.solvedSubmission(date, 3));

		ApiException e = assertThrows(ApiException.class, () -> this.daily.submit(player, this.solvedSubmission(date, 3)));
		assertAll(
			() -> assertEquals(ErrorCode.DAILY_ALREADY_SOLVED, e.code()),
			() -> assertEquals(409, e.status())
		);
	}

	@Test
	void submit_repeatedFailures_areAllowedAndIncrementTheAttemptNumber() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();
		DailyService.Submit failure = new DailyService.Submit(date, 3, DailyOutcome.FAILED, 60_000, 5, 0, List.of());

		DailyService.Submission first = this.daily.submit(player, failure);
		DailyService.Submission second = this.daily.submit(player, failure);

		assertAll(
			() -> assertEquals(1, first.result().attemptNo()),
			() -> assertEquals(2, second.result().attemptNo()),
			() -> assertEquals(0, second.streak().current(), "a failure must not touch the streak")
		);
	}

	@Test
	void submit_aSolveAfterFailures_isAcceptedAndRecordsTheAttemptNumber() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();
		this.daily.submit(player, new DailyService.Submit(date, 3, DailyOutcome.FAILED, 60_000, 5, 0, List.of()));

		DailyService.Submission solved = this.daily.submit(player, this.solvedSubmission(date, 3));

		assertAll(
			() -> assertEquals(2, solved.result().attemptNo()),
			() -> assertTrue(solved.verified()),
			() -> assertEquals(1, solved.streak().current())
		);
	}

	@Test
	void submit_aFutureDate_isRejected() {
		Principal player = this.player("Owner");
		LocalDate tomorrow = this.daily.today().plusDays(1);

		ApiException e = assertThrows(ApiException.class,
			() -> this.daily.submit(player, this.solvedSubmission(tomorrow, 3)));
		assertEquals(ErrorCode.DAILY_DATE_INVALID, e.code());
	}

	@Test
	void submit_aPastDate_isRejected() {
		Principal player = this.player("Owner");
		LocalDate yesterday = this.daily.today().minusDays(1);

		ApiException e = assertThrows(ApiException.class,
			() -> this.daily.submit(player, this.solvedSubmission(yesterday, 3)));
		assertEquals(ErrorCode.DAILY_DATE_INVALID, e.code());
	}

	@Test
	void submit_aSolveWithLisa_isRejected() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();
		DailyService.Submit submit = new DailyService.Submit(date, 6, DailyOutcome.SOLVED, 60_000, 0, 0, List.of());

		ApiException e = assertThrows(ApiException.class, () -> this.daily.submit(player, submit));
		assertEquals(ErrorCode.LISA_NOT_ALLOWED, e.code());
	}

	// --- verification (spec 8.2) ---

	@Test
	void submit_aSolveWithAWrongDigit_isStoredButUnverified() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();
		DailyService.Submit correct = this.solvedSubmission(date, 3);

		List<SolveVerifier.Entry> tampered = new ArrayList<>(correct.solveOrder());
		SolveVerifier.Entry first = tampered.getFirst();
		tampered.set(0, new SolveVerifier.Entry(first.cell(), first.digit() % 9 + 1));

		DailyService.Submission submission = this.daily.submit(player, new DailyService.Submit(date, 3,
			DailyOutcome.SOLVED, correct.elapsedMs(), 0, 0, tampered));

		assertAll(
			() -> assertTrue(submission.accepted(), "the result is still stored"),
			() -> assertFalse(submission.verified()),
			() -> assertEquals(0, submission.streak().current(), "an unverified solve must not build a streak")
		);
	}

	@Test
	void submit_anIncompleteSolve_isUnverified() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();
		DailyService.Submit correct = this.solvedSubmission(date, 3);
		List<SolveVerifier.Entry> partial = correct.solveOrder().subList(0, correct.solveOrder().size() - 1);

		DailyService.Submission submission = this.daily.submit(player,
			new DailyService.Submit(date, 3, DailyOutcome.SOLVED, correct.elapsedMs(), 0, 0, partial));

		assertFalse(submission.verified());
	}

	@Test
	void submit_aDuplicateCellEntry_isUnverified() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();
		DailyService.Submit correct = this.solvedSubmission(date, 3);

		List<SolveVerifier.Entry> duplicated = new ArrayList<>(correct.solveOrder());
		duplicated.add(duplicated.getFirst());

		DailyService.Submission submission = this.daily.submit(player,
			new DailyService.Submit(date, 3, DailyOutcome.SOLVED, correct.elapsedMs(), 0, 0, duplicated));

		assertFalse(submission.verified());
	}

	@Test
	void submit_anEntryForAGivenCell_isUnverified() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();
		DailyService.Submit correct = this.solvedSubmission(date, 3);
		GeneratedPuzzle puzzle = PuzzleFactory.generate(this.daily.keyFor(date, Difficulty.THREE));

		int givenIndex = -1;
		for (int index = 0; index < puzzle.puzzle().size().cellCount(); index++) {
			if (puzzle.puzzle().cell(index).isGiven()) {
				givenIndex = index;
				break;
			}
		}
		List<SolveVerifier.Entry> withGiven = new ArrayList<>(correct.solveOrder());
		withGiven.add(new SolveVerifier.Entry(givenIndex, puzzle.solutionAt(givenIndex)));

		DailyService.Submission submission = this.daily.submit(player,
			new DailyService.Submit(date, 3, DailyOutcome.SOLVED, correct.elapsedMs(), 0, 0, withGiven));

		assertFalse(submission.verified());
	}

	@Test
	void submit_anImplausiblyFastSolve_isUnverified() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();
		DailyService.Submit correct = this.solvedSubmission(date, 3);

		// "A full grid cannot be entered in two seconds" (spec 8.2).
		DailyService.Submission submission = this.daily.submit(player,
			new DailyService.Submit(date, 3, DailyOutcome.SOLVED, 2_000, 0, 0, correct.solveOrder()));

		assertFalse(submission.verified());
	}

	@Test
	void submit_anOutOfRangeCellIndex_isUnverified() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();
		DailyService.Submit correct = this.solvedSubmission(date, 3);

		List<SolveVerifier.Entry> bad = new ArrayList<>(correct.solveOrder());
		bad.set(0, new SolveVerifier.Entry(9999, 1));

		DailyService.Submission submission = this.daily.submit(player,
			new DailyService.Submit(date, 3, DailyOutcome.SOLVED, correct.elapsedMs(), 0, 0, bad));

		assertFalse(submission.verified());
	}

	// --- streaks (spec 8.3) ---

	@Test
	void streak_solvingOnConsecutiveDays_increments() {
		Principal player = this.player("Owner");
		this.daily.submit(player, this.solvedSubmission(this.daily.today(), 3));

		this.now.set(NOW.plus(java.time.Duration.ofDays(1)));
		DailyService.Submission second = this.daily.submit(player, this.solvedSubmission(this.daily.today(), 3));

		assertAll(
			() -> assertEquals(2, second.streak().current()),
			() -> assertEquals(2, second.streak().longest())
		);
	}

	@Test
	void streak_afterAMissedDay_restartsAtOne() {
		Principal player = this.player("Owner");
		this.daily.submit(player, this.solvedSubmission(this.daily.today(), 3));

		this.now.set(NOW.plus(java.time.Duration.ofDays(3)));
		DailyService.Submission later = this.daily.submit(player, this.solvedSubmission(this.daily.today(), 3));

		assertAll(
			() -> assertEquals(1, later.streak().current(), "a gap resets the run"),
			() -> assertEquals(1, later.streak().longest(), "but the best ever is remembered")
		);
	}

	@Test
	void streak_isIdempotentForOneDate() {
		// Spec 8.3 requires idempotence; re-crediting the same day must not inflate the count.
		net.luis.sudoku.domain.Streak streak = net.luis.sudoku.domain.Streak.none(UUID.randomUUID());
		LocalDate date = LocalDate.of(2026, 7, 25);

		net.luis.sudoku.domain.Streak once = streak.completedOn(date);
		net.luis.sudoku.domain.Streak twice = once.completedOn(date);

		assertAll(
			() -> assertEquals(1, once.current()),
			() -> assertEquals(1, twice.current())
		);
	}

	// --- leaderboard (spec 8.6) ---

	@Test
	void leaderboard_ranksVerifiedSolvesFastestFirstWithinOneTier() {
		Principal fast = this.player("Owner");
		Principal slow = this.player("Slow");
		LocalDate date = this.daily.today();

		DailyService.Submit base = this.solvedSubmission(date, 3);
		this.daily.submit(slow, new DailyService.Submit(date, 3, DailyOutcome.SOLVED, base.elapsedMs() * 2, 0, 0,
			base.solveOrder()));
		this.daily.submit(fast, base);

		List<DailyLeaderboardRepository.Entry> ranking = this.daily.leaderboard(3);

		assertAll(
			() -> assertEquals(2, ranking.size()),
			() -> assertEquals("Owner", ranking.getFirst().displayName()),
			() -> assertEquals("Slow", ranking.get(1).displayName())
		);
	}

	@Test
	void leaderboard_excludesUnverifiedSolves() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();
		DailyService.Submit correct = this.solvedSubmission(date, 3);

		this.daily.submit(player, new DailyService.Submit(date, 3, DailyOutcome.SOLVED, 1_000, 0, 0,
			correct.solveOrder()));

		assertTrue(this.daily.leaderboard(3).isEmpty());
	}

	@Test
	void leaderboard_showsOnlyTheRequestedTier() {
		Principal player = this.player("Owner");
		LocalDate date = this.daily.today();
		this.daily.submit(player, this.solvedSubmission(date, 3));

		assertAll(
			() -> assertEquals(1, this.daily.leaderboard(3).size()),
			() -> assertTrue(this.daily.leaderboard(4).isEmpty())
		);
	}

	@Test
	void leaderboard_forLisa_isRejected() {
		ApiException e = assertThrows(ApiException.class, () -> this.daily.leaderboard(6));
		assertEquals(ErrorCode.LISA_NOT_ALLOWED, e.code());
	}
}
