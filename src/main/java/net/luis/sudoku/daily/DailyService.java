package net.luis.sudoku.daily;

import net.luis.sudoku.config.ServerConfig;
import net.luis.sudoku.currency.CurrencyService;
import net.luis.sudoku.currency.LedgerReason;
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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The daily puzzle: key issuance, difficulty preference, result verification and streaks
 * (server-spec 8).
 * <p>
 * The server issues a {@link PuzzleKey} and never the grid. The client regenerates it locally through
 * the same shared-core version, which is what lets the daily work offline.
 */
public final class DailyService {
	
	private static final Logger log = LoggerFactory.getLogger(DailyService.class);
	/**
	 * The longest run {@link #syncStreak} will adopt from a client's own count.
	 * <p>
	 * Not a security boundary - the claim is unverified whatever the number - but a self-reported count is
	 * the one input here with no upper bound of its own, and a corrupt or absurd one should be refused
	 * rather than stored as somebody's record.
	 */
	private static final int MAX_SYNCED_STREAK = 3650;
	/**
	 * How many generated dailies to keep.
	 * <p>
	 * Sized from the scale rather than guessed at: a single date can want a grid per tier, and there are
	 * {@link Difficulty#LISA}{@code .index()} of them. Two dates' worth is the working set, because an
	 * offline queue draining after midnight verifies yesterday's tiers against yesterday's grids while
	 * today's are being issued. The cap is still what stops a server that has been up for months from
	 * holding a grid per date it has ever served.
	 * <p>
	 * This was eight while the scale had six tiers, which was a comfortable margin then and a cache
	 * smaller than one day's working set afterwards - it would have evicted grids that were still being
	 * asked for, and put the generation back on the request threads this cache exists to keep it off.
	 */
	private static final int CACHE_CAPACITY = 2 * Difficulty.LISA.index();
	/** Rhubarb per missed day repaired by spending a restore point. */
	public static final int RESTORE_COST_PER_DAY = 10;
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
	private final AtomicReference<LocalDate> lastRollover = new AtomicReference<>();
	/**
	 * Generated dailies, keyed by (date, tier), least recently <em>used</em> evicted first - see
	 * {@link #puzzleFor}.
	 * <p>
	 * Access ordered, which is the third constructor argument and the reason it is spelled out rather than
	 * defaulted. Insertion order would evict by age of generation, so the tier the most players are
	 * grinding all evening - the one entry that must never leave - would be dropped the moment fifteen
	 * newer tiers had been touched behind it, and the busiest grid on the server would be the one
	 * regenerated most.
	 * <p>
	 * Every access is inside {@code synchronized (this.puzzleCache)}: a {@link LinkedHashMap} is not thread
	 * safe and this is read from every request thread. A plain {@code ConcurrentHashMap} would not do the
	 * eviction, and the map is tiny enough that a lock held for a get or a put costs nothing measurable.
	 * Access ordering makes a {@code get} a structural modification, so the lock is now load bearing on the
	 * read path too, not merely defensive.
	 */
	private final Map<DailyPuzzleKey, GeneratedPuzzle> puzzleCache = new LinkedHashMap<>(16, 0.75F, true) {
		
		@Override
		protected boolean removeEldestEntry(Map.Entry<DailyPuzzleKey, GeneratedPuzzle> eldest) {
			return this.size() > CACHE_CAPACITY;
		}
	};
	
	public DailyService(
		@NonNull Database database, @NonNull ServerConfig config, @NonNull String serverId, @NonNull PreferenceRepository preferences, @NonNull DailyResultRepository results,
		@NonNull StreakRepository streaks, @NonNull DailyLeaderboardRepository leaderboard, @NonNull CurrencyService currency, @NonNull StatsService stats, @NonNull Clock clock
	) {
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
		return PuzzleFactory.singlePlayerKey(this.config.dailySize(), this.config.dailyVariant(), difficulty, seed);
	}
	
	/**
	 * The generated daily for a date and tier, from a small in-memory cache.
	 * <p>
	 * The daily deliberately bypasses {@code PuzzleQueue}, because the queue invents its own seeds and the
	 * daily's is fixed by {@code serverId ‖ date}. That left every issuance and every result verification
	 * generating the grid from scratch on a request thread - which at the fifteen-band tiers is up to about
	 * a second - for a puzzle that is the same for every player all day long. There are at most
	 * {@link #CACHE_CAPACITY} live (date, tier) pairs in practice: today's tiers, plus yesterday's while an
	 * offline queue drains.
	 * <p>
	 * Deliberately <em>not</em> folded into {@link #keyFor}, which stays pure so issuance and verification
	 * agree by construction rather than by both happening to hit the same cache entry.
	 *
	 * @param date The daily's date in the server zone
	 * @param difficulty The tier locked in for that date
	 * @return The generated puzzle
	 */
	public @NonNull GeneratedPuzzle puzzleFor(@NonNull LocalDate date, @NonNull Difficulty difficulty) {
		DailyPuzzleKey cacheKey = new DailyPuzzleKey(date, difficulty);
		
		GeneratedPuzzle cached;
		synchronized (this.puzzleCache) {
			cached = this.puzzleCache.get(cacheKey);
		}
		if (cached != null) {
			return cached;
		}
		
		// Generated outside the lock: this is the expensive part, and two requests racing on a cold cache
		// producing the same grid twice is far cheaper than every other daily request blocking behind one.
		GeneratedPuzzle generated = PuzzleFactory.generate(this.keyFor(date, difficulty));
		synchronized (this.puzzleCache) {
			GeneratedPuzzle raced = this.puzzleCache.putIfAbsent(cacheKey, generated);
			return raced == null ? generated : raced;
		}
	}
	
	public int preference(@NonNull Principal actor) {
		return this.database.read(connection -> this.preferences.dailyDifficulty(connection, actor.userId()));
	}
	
	/**
	 * Sets the standing daily difficulty. The change takes effect from the next day only, because
	 * today's tier is already locked in {@code daily_assignments} (spec 8.1).
	 */
	public void setPreference(@NonNull Principal actor, int difficultyIndex) {
		PuzzleFactory.singlePlayerDifficultyOfIndex(difficultyIndex);
		Instant now = this.clock.instant();
		this.database.execute(connection -> this.preferences.setDailyDifficulty(connection, actor.userId(), difficultyIndex, now));
	}
	
	/**
	 * Records a daily attempt (spec 8.2, 8.3).
	 *
	 * @throws ApiException {@code DAILY_DATE_INVALID} for a future date;
	 *   {@code DAILY_ALREADY_SOLVED} once a success exists for that date
	 */
	public @NonNull Submission submit(@NonNull Principal actor, @NonNull Submit submit) {
		LocalDate today = this.today();
		this.requireSubmittableDate(submit.date(), today);
		
		Difficulty difficulty = PuzzleFactory.singlePlayerDifficultyOfIndex(submit.difficulty());
		// Through the cache, not PuzzleFactory directly: verification replays against the very same grid
		// issuance handed out, and on the day's busiest tier that is one generation rather than one per
		// submitted result.
		GeneratedPuzzle puzzle = this.puzzleFor(submit.date(), difficulty);
		SolveVerifier.Verification verification = submit.outcome() == DailyOutcome.SOLVED
			? SolveVerifier.verify(puzzle, submit.solveOrder(), submit.elapsedMs())
			// A failure never claims a complete grid, so replaying it would always "fail". Only the
			// success path carries a verifiable claim.
			: new SolveVerifier.Verification(true, "");
		
		if (!verification.verified()) {
			// Warn, not info: a claimed solve the server cannot replay is either a client that built its
			// solveOrder wrong - the shape of bug that once stopped every daily from counting - or a
			// fabricated result. Neither should be invisible in a warn-only log, and it stays rare, because
			// an honest client never produces one.
			log.warn("Unverified daily result from {} on {}: {}", actor.userId(), submit.date(), verification.reason());
		}
		
		return this.database.transaction(connection -> {
			if (this.results.hasSolved(connection, actor.userId(), submit.date())) {
				throw new ApiException(ErrorCode.DAILY_ALREADY_SOLVED, "You have already solved this daily");
			}
			
			int attemptNo = this.results.nextAttemptNo(connection, actor.userId(), submit.date(), submit.difficulty());
			DailyResult stored = this.results.insert(connection, actor.userId(), submit.date(), submit.difficulty(), attemptNo, submit.outcome(), submit.elapsedMs(), submit.mistakes(), submit.hintsUsed(), verification.verified(), this.clock.instant());
			
			Streak streak = this.streaks.findForUpdate(connection, actor.userId());
			int awarded = 0;
			if (submit.outcome() == DailyOutcome.SOLVED && verification.verified()) {
				// Credited to the date played, not the date submitted, so an offline queue draining a day
				// late still lands on the right day (spec 8.4).
				streak = streak.completedOn(submit.date());
				this.streaks.save(connection, streak);
				this.leaderboard.record(connection, actor.userId(), submit.date(), submit.difficulty(), submit.elapsedMs(), attemptNo, submit.hintsUsed());
				// Currency is minted only on a verified success, in the same transaction as the result
				// (spec 9a.1). The daily bonus sits outside the normal-game cap.
				awarded = this.currency.awardForDaily(connection, actor.userId(), difficulty, puzzle.key().size(), submit.date());
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
	
	/**
	 * Adopts a streak a client counted locally, when it knows about more days than the server does
	 * (spec 8.3).
	 * <p>
	 * The gap this closes: the server's count only ever moves on a replay-verified {@code SOLVED}, so a
	 * daily solved while the server was unreachable lives on the device until its queued submission
	 * lands - and if that submission is lost, the day is gone from the server's side for good with no way
	 * to report it afterwards. Existing installs are in exactly that position, having queued dailies in a
	 * format the current client can no longer submit.
	 * <p>
	 * Unverified by nature, which is why {@link Streak#mergedWith} keeps it one-way and refuses to mint
	 * restore points from it. Safe to call on every reconnect: a claim that adds nothing is a no-op, so
	 * the client does not have to remember whether it has published before.
	 *
	 * @throws ApiException {@code BAD_REQUEST} for a negative count, a date in the future, or a run longer
	 *   than {@link #MAX_SYNCED_STREAK} days
	 */
	public @NonNull Streak syncStreak(@NonNull Principal actor, int claimedCurrent, @NonNull LocalDate claimedLastCompleted) {
		if (claimedCurrent < 0) {
			throw ApiException.badRequest("streak must not be negative, got: " + claimedCurrent);
		}
		if (claimedCurrent > MAX_SYNCED_STREAK) {
			throw ApiException.badRequest("streak is longer than " + MAX_SYNCED_STREAK + " days, got: " + claimedCurrent);
		}
		if (claimedLastCompleted.isAfter(this.today())) {
			throw new ApiException(ErrorCode.DAILY_DATE_INVALID, "That streak ends in the future");
		}
		
		return this.database.transaction(connection -> {
			Streak stored = this.streaks.findForUpdate(connection, actor.userId());
			Streak merged = stored.mergedWith(claimedCurrent, claimedLastCompleted);
			if (merged.equals(stored)) {
				return stored;
			}
			this.streaks.save(connection, merged);
			log.info("Adopted client streak {} (ending {}) for user {}, was {}", claimedCurrent, claimedLastCompleted, actor.userId(), stored.current());
			return merged;
		});
	}
	
	/**
	 * Spends banked restore points to repair a broken streak, patching it up to yesterday so today's
	 * ordinary {@link #submit} sees a consecutive continuation.
	 *
	 * @throws ApiException {@code STREAK_RESTORE_NOT_NEEDED} if the streak has never started, or there is
	 *   no gap to repair; {@code INSUFFICIENT_RESTORE_POINTS} if there are fewer banked points than missed
	 *   days; {@code INSUFFICIENT_BALANCE} if the player cannot afford the Rhubarb cost
	 */
	public @NonNull Streak restoreStreak(@NonNull Principal actor) {
		LocalDate today = this.today();
		
		return this.database.transaction(connection -> {
			Streak streak = this.streaks.findForUpdate(connection, actor.userId());
			LocalDate lastCompleted = streak.lastCompletedDate();
			if (lastCompleted == null) {
				throw new ApiException(ErrorCode.STREAK_RESTORE_NOT_NEEDED, "There is no streak to restore");
			}
			
			long gap = ChronoUnit.DAYS.between(lastCompleted, today);
			int missedDays = (int) (gap - 1);
			if (missedDays <= 0) {
				throw new ApiException(ErrorCode.STREAK_RESTORE_NOT_NEEDED, "There is no gap to restore");
			}
			if (missedDays > streak.restorePoints()) {
				throw new ApiException(ErrorCode.INSUFFICIENT_RESTORE_POINTS, "You need " + missedDays + " restore points, but only have " + streak.restorePoints());
			}
			
			this.currency.spend(connection, actor.userId(), missedDays * RESTORE_COST_PER_DAY, LedgerReason.SPEND_STREAK_RESTORE);
			Streak restored = streak.restoredBy(missedDays, today.minusDays(1));
			this.streaks.save(connection, restored);
			return restored;
		});
	}
	
	public @NonNull List<DailyLeaderboardRepository.Entry> leaderboard(int difficultyIndex) {
		PuzzleFactory.singlePlayerDifficultyOfIndex(difficultyIndex);
		LocalDate date = this.today();
		return this.database.read(connection -> this.leaderboard.ranking(connection, date, difficultyIndex));
	}
	
	/**
	 * Accepts a result for any date that has actually happened.
	 * <p>
	 * Today-only was a rule that quietly cancelled the feature it was supposed to serve. Spec 8.3.1 says a
	 * daily finished without a reachable server "is queued locally and submitted on the next successful
	 * connection", and the whole queue is built for it - the row carries its own date so credit stays
	 * pinned to the day played, and {@link Streak#completedOn} is written to take a date that is not
	 * today. But a queue drains *after* the outage, which is by definition later, and the first thing this
	 * method did was refuse it. A player offline overnight lost the day, was told nothing, and the client
	 * dropped the row as permanently rejected. Every offline daily this app has ever queued died here.
	 * <p>
	 * No lower bound at all, by the owner's decision: a bounded window is still a deadline, and a queue
	 * that misses it loses the day just as silently as today-only did. What a lower bound buys is not
	 * integrity - the daily is a deterministic derivation of a public {@code serverId} and date, so any
	 * past puzzle is precomputable and spec 12 already accepts that - but only a limit on how far back a
	 * player can fill in. The streak has never been evidence of having played on the day, only of having
	 * solved the puzzle for it. {@link Streak#completedOn} ignores a date that is not after the last one
	 * completed, so an old submission can extend a run forwards but never rewrite one.
	 *
	 * @throws ApiException {@code DAILY_DATE_INVALID} for a future date
	 */
	private void requireSubmittableDate(@NonNull LocalDate date, @NonNull LocalDate today) {
		if (date.isAfter(today)) {
			throw new ApiException(ErrorCode.DAILY_DATE_INVALID, "That daily is in the future");
		}
	}
	
	/**
	 * @param date the daily's date in the server zone
	 * @param key everything the client needs to regenerate the grid locally
	 */
	public record Daily(@NonNull LocalDate date, @NonNull PuzzleKey key) {}
	
	/**
	 * One cache slot: the two things that decide which grid a daily is - see {@link #puzzleFor}.
	 *
	 * @param date the daily's date in the server zone
	 * @param difficulty the tier locked in for it
	 */
	private record DailyPuzzleKey(@NonNull LocalDate date, @NonNull Difficulty difficulty) {}
	
	/**
	 * A submitted attempt.
	 *
	 * @param date the date played, which the offline queue may report late
	 * @param difficulty tier index 1-15, Lisa included
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
	public record Submission(boolean accepted, boolean verified, @NonNull DailyResult result, @NonNull Streak streak, int currencyAwarded) {}
}
