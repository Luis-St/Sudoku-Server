package net.luis.sudoku.daily;

import net.luis.sudoku.config.ServerConfig;
import net.luis.sudoku.currency.CurrencyService;
import net.luis.sudoku.db.Database;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.key.PuzzleKey;
import net.luis.sudoku.puzzle.PuzzleFactory;
import net.luis.sudoku.repository.*;
import net.luis.sudoku.stats.StatsService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.List;
import java.util.UUID;

/**
 * The daily puzzle: key issuance, difficulty preference, result verification and streaks
 * (server-spec 8).
 * <p>
 * The server issues a {@link PuzzleKey} and never the grid. The client regenerates it locally through
 * the same shared-core version, which is what lets the daily work offline.
 */
public final class DailyService {
	
	private static final Logger log = LoggerFactory.getLogger(DailyService.class);
	
	private final Database database;
	private final ServerConfig config;
	private final String serverId;
	private final PreferenceRepository preferences;
	private final DailyResultRepository results;
	private final StreakRepository streaks;
	private final DailyLeaderboardRepository leaderboard;
	private final CurrencyService currency;
	private final StatsService stats;
	private final Clock clock;
	
	/** Last date the rollover job ran, so it is attempted once per date rather than per request. */
	private final java.util.concurrent.atomic.AtomicReference<LocalDate> lastRollover =
		new java.util.concurrent.atomic.AtomicReference<>();
	
	public DailyService(@NonNull Database database, @NonNull ServerConfig config, @NonNull String serverId,
	                    @NonNull PreferenceRepository preferences, @NonNull DailyResultRepository results,
	                    @NonNull StreakRepository streaks, @NonNull DailyLeaderboardRepository leaderboard,
	                    @NonNull CurrencyService currency, @NonNull StatsService stats, @NonNull Clock clock) {
		this.database = database;
		this.config = config;
		this.serverId = serverId;
		this.preferences = preferences;
		this.results = results;
		this.streaks = streaks;
		this.leaderboard = leaderboard;
		this.currency = currency;
		this.stats = stats;
		this.clock = clock;
	}
	
	/**
	 * @return today's date in the server's configured rollover zone
	 */
	public @NonNull LocalDate today() {
		return LocalDate.ofInstant(this.clock.instant(), this.config.timezone());
	}
	
	/**
	 * Issues today's daily key for the caller, locking in their tier for the day if this is their first
	 * request of it.
	 */
	public @NonNull Daily today(@NonNull Principal actor) {
		LocalDate date = this.today();
		this.rolloverIfNeeded(date);
		
		int difficultyIndex = this.database.transaction(connection -> {
			int preferred = this.preferences.dailyDifficulty(connection, actor.userId());
			// First request of the day fixes the tier; later ones read back what was fixed.
			return this.preferences.assign(connection, actor.userId(), date, preferred);
		});
		
		return new Daily(date, this.keyFor(date, Difficulty.ofIndex(difficultyIndex)));
	}
	
	/**
	 * Builds the key for a date and tier. Pure: no state, so verification and issuance agree by
	 * construction.
	 */
	public @NonNull PuzzleKey keyFor(@NonNull LocalDate date, @NonNull Difficulty difficulty) {
		long seed = SeedDerivation.seedFor(this.serverId, date);
		return PuzzleFactory.key(this.config.dailySize(), this.config.dailyVariant(), difficulty, seed);
	}
	
	public int preference(@NonNull Principal actor) {
		return this.database.read(connection -> this.preferences.dailyDifficulty(connection, actor.userId()));
	}
	
	/**
	 * Sets the standing daily difficulty. The change takes effect from the next day only, because
	 * today's tier is already locked in {@code daily_assignments} (spec 8.1).
	 */
	public void setPreference(@NonNull Principal actor, int difficultyIndex) {
		PuzzleFactory.difficultyOfIndex(difficultyIndex);
		Instant now = this.clock.instant();
		this.database.execute(connection -> this.preferences.setDailyDifficulty(connection, actor.userId(), difficultyIndex, now));
	}
	
	/**
	 * Records a daily attempt (spec 8.2, 8.3).
	 *
	 * @throws ApiException {@code DAILY_DATE_INVALID} for a past or future date;
	 *   {@code DAILY_ALREADY_SOLVED} once a success exists for that date
	 */
	public @NonNull Submission submit(@NonNull Principal actor, @NonNull Submit submit) {
		LocalDate today = this.today();
		this.requireSubmittableDate(submit.date(), today);
		
		Difficulty difficulty = PuzzleFactory.difficultyOfIndex(submit.difficulty());
		GeneratedPuzzle puzzle = PuzzleFactory.generate(this.keyFor(submit.date(), difficulty));
		SolveVerifier.Verification verification = submit.outcome() == DailyOutcome.SOLVED
			? SolveVerifier.verify(puzzle, submit.solveOrder(), submit.elapsedMs())
			// A failure never claims a complete grid, so replaying it would always "fail". Only the
			// success path carries a verifiable claim.
			: new SolveVerifier.Verification(true, "");
		
		if (!verification.verified()) {
			log.info("Unverified daily result from {} on {}: {}", actor.userId(), submit.date(), verification.reason());
		}
		
		return this.database.transaction(connection -> {
			if (this.results.hasSolved(connection, actor.userId(), submit.date())) {
				throw new ApiException(ErrorCode.DAILY_ALREADY_SOLVED, "You have already solved this daily");
			}
			
			int attemptNo = this.results.nextAttemptNo(connection, actor.userId(), submit.date(), submit.difficulty());
			DailyResult stored = this.results.insert(connection, actor.userId(), submit.date(), submit.difficulty(),
				attemptNo, submit.outcome(), submit.elapsedMs(), submit.mistakes(), submit.hintsUsed(),
				verification.verified());
			
			Streak streak = this.streaks.findForUpdate(connection, actor.userId());
			int awarded = 0;
			if (submit.outcome() == DailyOutcome.SOLVED && verification.verified()) {
				// Credited to the date played, not the date submitted, so an offline queue draining a day
				// late still lands on the right day (spec 8.4).
				streak = streak.completedOn(submit.date());
				this.streaks.save(connection, streak);
				this.leaderboard.record(connection, actor.userId(), submit.date(), submit.difficulty(),
					submit.elapsedMs(), attemptNo, submit.hintsUsed());
				// Currency is minted only on a verified success, in the same transaction as the result
				// (spec 9a.1). The daily bonus sits outside the normal-game cap.
				awarded = this.currency.awardForDaily(connection, actor.userId(), difficulty, submit.date());
			}
			
			return new Submission(true, verification.verified(), stored, streak, awarded);
		});
	}
	
	/**
	 * Runs the fold-and-prune job at most once per date (spec 8.6).
	 * <p>
	 * Triggered lazily by the first daily request of a new date rather than by a scheduler, so a server
	 * that was down over midnight still rolls over on its next request. The in-memory guard only avoids
	 * redundant work - correctness comes from the advisory lock inside
	 * {@link StatsService#runRollover()}, since several requests can cross midnight together.
	 */
	private void rolloverIfNeeded(@NonNull LocalDate date) {
		if (date.equals(this.lastRollover.get())) {
			return;
		}
		if (!this.lastRollover.compareAndSet(this.lastRollover.get(), date)) {
			return;
		}
		try {
			this.stats.runRollover();
		} catch (RuntimeException e) {
			// A failed rollover must not fail the player's daily request; the next one retries.
			this.lastRollover.set(null);
			log.warn("Daily rollover failed for {}", date, e);
		}
	}
	
	public @NonNull Streak streak(@NonNull UUID userId) {
		return this.database.read(connection -> this.streaks.find(connection, userId));
	}
	
	public @NonNull List<DailyLeaderboardRepository.Entry> leaderboard(int difficultyIndex) {
		PuzzleFactory.difficultyOfIndex(difficultyIndex);
		LocalDate date = this.today();
		return this.database.read(connection -> this.leaderboard.ranking(connection, date, difficultyIndex));
	}
	
	/**
	 * The daily is accepted only for the current date in the server zone (spec 8.3).
	 * <p>
	 * Yesterday is refused too. That is stricter than the offline queue would ideally like, but the
	 * spec is explicit that past dates are rejected, and accepting them would let a player bank an
	 * unlimited backlog of "streak days" to submit at leisure.
	 */
	private void requireSubmittableDate(@NonNull LocalDate date, @NonNull LocalDate today) {
		if (date.isAfter(today)) {
			throw new ApiException(ErrorCode.DAILY_DATE_INVALID, "That daily is in the future");
		}
		if (date.isBefore(today)) {
			throw new ApiException(ErrorCode.DAILY_DATE_INVALID, "That daily is no longer open");
		}
	}
	
	/**
	 * @param date the daily's date in the server zone
	 * @param key everything the client needs to regenerate the grid locally
	 */
	public record Daily(@NonNull LocalDate date, @NonNull PuzzleKey key) {}
	
	/**
	 * A submitted attempt.
	 *
	 * @param date the date played, which the offline queue may report late
	 * @param difficulty tier index 1-5
	 * @param outcome how it ended
	 * @param elapsedMs wall time
	 * @param mistakes incorrect entries
	 * @param hintsUsed hints consumed
	 * @param solveOrder the ordered entries committed, replayed for verification
	 */
	public record Submit(
		@NonNull LocalDate date,
		int difficulty,
		@NonNull DailyOutcome outcome,
		long elapsedMs,
		int mistakes,
		int hintsUsed,
		@NonNull List<SolveVerifier.Entry> solveOrder
	) {}
	
	/**
	 * @param accepted whether the result was stored
	 * @param verified whether the solve-order replay passed
	 * @param result the stored row
	 * @param streak the streak after this submission
	 * @param currencyAwarded Rhubarb minted by this submission, 0 unless it was a verified first solve
	 */
	public record Submission(boolean accepted, boolean verified, @NonNull DailyResult result, @NonNull Streak streak,
	                         int currencyAwarded) {}
}
