package net.luis.sudoku.stats;

import net.luis.sudoku.config.ServerConfig;
import net.luis.sudoku.db.AdvisoryLocks;
import net.luis.sudoku.db.Database;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.repository.*;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.query.row.SqlRow5;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.*;
import java.util.List;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Statistics storage, the offline-to-online sync, and the daily rollover job (server-spec 9).
 */
public final class StatsService {
	
	private static final Logger log = LoggerFactory.getLogger(StatsService.class);
	
	/** Guards against a client uploading an implausible history in one call. */
	private static final int MAX_SYNC_ENTRIES = 200;
	
	/**
	 * How many finished games one {@code POST /stats/games} may carry.
	 * <p>
	 * A client normally sends one, the game it just finished; a batch this size only turns up after a long
	 * offline stretch, and a queue longer than that is flushed over several calls rather than in one.
	 */
	private static final int MAX_GAMES_PER_CALL = 50;
	
	/**
	 * How long a game's id is remembered for the sake of turning away a retry.
	 * <p>
	 * A client retries a queued upload until it succeeds, so the window has to cover an offline stretch
	 * rather than a network timeout - but it is a window all the same, because remembering every id for
	 * ever would grow a table the size of every game ever played to guard against a retry nobody is still
	 * making.
	 */
	private static final Duration RECORDED_GAME_RETENTION = Duration.ofDays(30);
	
	private final Database database;
	private final StatsRepository stats;
	private final UserRepository users;
	private final StreakRepository streaks;
	private final DailyResultRepository dailyResults;
	private final DailyLeaderboardRepository leaderboard;
	private final RecordedGameRepository recordedGames;
	private final ServerConfig config;
	private final Clock clock;
	
	public StatsService(
		@NonNull Database database, @NonNull StatsRepository stats, @NonNull UserRepository users, @NonNull StreakRepository streaks, @NonNull DailyResultRepository dailyResults,
		@NonNull DailyLeaderboardRepository leaderboard, @NonNull RecordedGameRepository recordedGames, @NonNull ServerConfig config, @NonNull Clock clock
	) {
		this.database = database;
		this.stats = stats;
		this.users = users;
		this.streaks = streaks;
		this.dailyResults = dailyResults;
		this.leaderboard = leaderboard;
		this.recordedGames = recordedGames;
		this.config = config;
		this.clock = clock;
	}
	
	private static @Nullable String lastSeen(@NonNull SqlTransaction connection, @NonNull UUID userId) throws SqlException {
		Instant seen = connection.from(DEVICES).select(Sql.max(DEVICE_LAST_SEEN_AT))
			.where(Sql.equalTo(DEVICE_USER_ID, userId)).fetchOneOrNull();
		return seen == null ? null : seen.toString();
	}
	
	public @NonNull List<StatsEntry> forUser(@NonNull UUID userId) {
		return this.database.read(connection -> this.stats.findByUser(connection, userId));
	}
	
	/**
	 * The player browser's list (spec 9).
	 * <p>
	 * A kicked player is not a player any more, so they are absent from everyone's list - except an
	 * admin's. A kick is reversible (spec 7.2), and this is the only listing there is: without the removed
	 * rows an admin has nowhere to reinstate anybody <em>from</em>, since a kicked account is by definition
	 * not going to show up again on its own.
	 *
	 * @param includeRevoked whether kicked players are included, which the caller decides from
	 *   {@link net.luis.sudoku.permission.Permission#CAN_KICK} - never from a request parameter
	 * @return every player the caller may see, with the summary the browser shows
	 */
	public @NonNull List<PlayerSummary> players(boolean includeRevoked) {
		return this.database.read(connection -> {
			List<PlayerSummary> summaries = new java.util.ArrayList<>();
			for (User user : this.users.findAll(connection)) {
				if (user.revoked() && !includeRevoked) {
					continue;
				}
				
				summaries.add(new PlayerSummary(
					user.id(),
					user.displayName(),
					user.role().name(),
					this.streaks.find(connection, user.id()).current(),
					lastSeen(connection, user.id()),
					user.revoked()
				));
			}
			return summaries;
		});
	}
	
	/**
	 * Merges a client's locally accumulated single-player history, once, on the first connection
	 * (spec 9).
	 * <p>
	 * Local <strong>daily streaks are deliberately not merged</strong>: the server's dailies differ from
	 * the {@code "local"} ones the client generated offline, so a server streak starts fresh.
	 *
	 * @return how many aggregate rows were merged
	 */
	public int sync(@NonNull Principal actor, @NonNull List<SyncEntry> entries) {
		if (entries.size() > MAX_SYNC_ENTRIES) {
			throw ApiException.badRequest("stats sync carries more entries than there are size/variant/tier combinations");
		}
		
		this.database.execute(connection -> {
			for (SyncEntry entry : entries) {
				entry.validate();
				this.stats.merge(connection, actor.userId(), entry.size(), entry.variant(), entry.difficulty(),
					entry.gamesPlayed(), entry.solved(), entry.failed(), entry.bestTimeMs(), entry.totalTimeMs(),
					entry.hintsUsed());
			}
		});
		
		log.info("Merged {} local stats entries for user {}", entries.size(), actor.userId());
		return entries.size();
	}
	
	/**
	 * Records a finished game into the aggregates, inside the caller's transaction.
	 */
	public void record(@NonNull SqlTransaction connection, @NonNull UUID userId, @NonNull GridSize size, @NonNull Variant variant, int difficulty, boolean solved, long elapsedMs, int hintsUsed) throws SqlException {
		this.stats.record(connection, userId, size.n(), variant.name(), difficulty, solved, elapsedMs, hintsUsed);
	}
	
	/**
	 * Folds finished single-player games into the aggregates as they are played (spec 9).
	 * <p>
	 * This is what keeps a player's statistics current. {@link #sync} is the one-off import of whatever
	 * history a device had accumulated before it ever reached a server, and it cannot serve this purpose:
	 * it uploads the whole local history in one call and every column it writes increments, so calling it
	 * a second time adds that history again. A game arrives here once, named by an id its client generated
	 * when the game ended.
	 * <p>
	 * <strong>Repeating a call is safe, and has to be.</strong> The client queues a game it could not
	 * upload and retries on the next successful connection, and a retry is indistinguishable - from the
	 * client's side - from a request whose response was lost on the way back. So each game is claimed in
	 * {@code recorded_games} before it is folded, in the same transaction, and an id already claimed is
	 * skipped rather than added. The whole batch is one transaction for the same reason: a partial failure
	 * that left some games folded and some not would be re-sent in full, and only the claims make that
	 * harmless.
	 *
	 * @return how many games were folded in, which excludes the ones recognised as retries
	 */
	public int recordGames(@NonNull Principal actor, @NonNull List<PlayedGame> games) {
		if (games.size() > MAX_GAMES_PER_CALL) {
			throw ApiException.badRequest("stats upload carries more than " + MAX_GAMES_PER_CALL + " games; send the queue in smaller batches");
		}
		for (PlayedGame game : games) {
			game.validate();
		}
		
		Instant now = this.clock.instant();
		int recorded = this.database.transaction(connection -> {
			int folded = 0;
			for (PlayedGame game : games) {
				if (!this.recordedGames.claim(connection, actor.userId(), game.gameId(), now)) {
					continue;
				}
				this.stats.record(connection, actor.userId(), game.size(), game.variant(), game.difficulty(),
					game.solved(), game.elapsedMs(), game.hintsUsed());
				folded++;
			}
			return folded;
		});
		
		if (recorded < games.size()) {
			log.debug("Dropped {} already-recorded games from user {}'s upload", games.size() - recorded, actor.userId());
		}
		return recorded;
	}
	
	/**
	 * Folds finished days into {@code stats} and prunes them (spec 8.6, 9).
	 * <p>
	 * Triggered lazily on the first daily request of a new date. An advisory lock keeps two requests
	 * crossing midnight from both running it, which would double-count every folded result.
	 *
	 * @return how many daily results were folded
	 */
	public int runRollover() {
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), this.config.timezone());
		
		return this.database.transaction(connection -> {
			try {
				Database.advisoryTransactionLock(connection.getConnection(), AdvisoryLocks.DAILY_ROLLOVER);
			} catch (SQLException e) {
				throw new SqlException("Failed to take the daily-rollover lock", e);
			}
			
			List<SqlRow5<UUID, Integer, DailyOutcome, Long, Integer>> unfolded = connection.from(DAILY_RESULTS)
				.select(RESULT_USER_ID, RESULT_DIFFICULTY, RESULT_OUTCOME, RESULT_ELAPSED_MS, RESULT_HINTS_USED)
				.where(Sql.lessThan(RESULT_DATE, today))
				.where(Sql.equalTo(RESULT_VERIFIED, true))
				.fetch();
			
			for (SqlRow5<UUID, Integer, DailyOutcome, Long, Integer> row : unfolded) {
				this.stats.record(connection, row.first(), this.config.dailySize().n(), this.config.dailyVariant().name(),
					row.second(), row.third() == DailyOutcome.SOLVED, row.fourth(), row.fifth());
			}
			int folded = unfolded.size();
			
			// Prune only after the fold, and in the same transaction, so a crash between the two cannot
			// lose results (spec 8.6).
			this.dailyResults.pruneBefore(connection, today);
			this.leaderboard.pruneBefore(connection, today);
			// Claims are pruned by their own age rather than by `today`: they guard against a client
			// retrying an upload, which has nothing to do with which day the game was played on.
			this.recordedGames.deleteBefore(connection, this.clock.instant().minus(RECORDED_GAME_RETENTION));
			
			if (folded > 0) {
				log.info("Rollover folded {} daily results into stats and pruned earlier dates", folded);
			}
			return folded;
		});
	}
	
	/**
	 * A player as shown in the browser (spec 9).
	 *
	 * @param id player id
	 * @param displayName their name
	 * @param role their role
	 * @param streak current daily streak
	 * @param lastSeenAt ISO-8601 last authentication, or null
	 */
	/**
	 * @param revoked whether this player has been kicked - only ever true for a caller allowed to see them,
	 *   and what lets that caller offer to reinstate them
	 */
	public record PlayerSummary(@NonNull UUID id, @NonNull String displayName, @NonNull String role, int streak, @Nullable String lastSeenAt,
	                            boolean revoked) {}
	
	/**
	 * One finished single-player game, uploaded as it happens.
	 * <p>
	 * The counterpart of {@link SyncEntry}, which carries a whole tier's aggregate: this is one game, and
	 * {@link #gameId} is what lets the same one be sent twice without being counted twice.
	 *
	 * @param gameId the client's id for this game, generated once when it ended
	 * @param size grid edge length
	 * @param variant {@code CLASSIC} or {@code CHAOS}
	 * @param difficulty tier index 1-15; Lisa is allowed, as in {@link SyncEntry}
	 * @param solved whether the player finished the grid, as opposed to running out of lives or giving up
	 * @param elapsedMs how long it took; only counted towards solve times when {@link #solved}
	 * @param hintsUsed hints consumed
	 */
	public record PlayedGame(@NonNull UUID gameId, int size, @NonNull String variant, int difficulty, boolean solved, long elapsedMs, int hintsUsed) {
		
		void validate() {
			try {
				GridSize.ofEdgeLength(this.size);
				Variant.valueOf(this.variant);
			} catch (IllegalArgumentException e) {
				throw ApiException.badRequest("Unsupported size/variant in stats upload: " + this.size + "/" + this.variant);
			}
			if (this.difficulty < 1 || this.difficulty > Difficulty.LISA.index()) {
				throw ApiException.badRequest("difficulty must be between 1 and " + Difficulty.LISA.index() + ", got: " + this.difficulty);
			}
			if (this.elapsedMs < 0 || this.hintsUsed < 0) {
				throw ApiException.badRequest("stats upload counters must not be negative");
			}
		}
	}
	
	/**
	 * One uploaded aggregate from a client's local history.
	 *
	 * @param size grid edge length
	 * @param variant {@code CLASSIC} or {@code CHAOS}
	 * @param difficulty tier index 1-15; Lisa is allowed here because it is a real single-player tier
	 * @param gamesPlayed games finished
	 * @param solved successes
	 * @param failed failures
	 * @param bestTimeMs fastest solve, or null
	 * @param totalTimeMs summed solve time
	 * @param hintsUsed hints consumed
	 */
	public record SyncEntry(int size, @NonNull String variant, int difficulty, int gamesPlayed, int solved, int failed, @Nullable Long bestTimeMs, long totalTimeMs, int hintsUsed) {
		
		void validate() {
			try {
				GridSize.ofEdgeLength(this.size);
				Variant.valueOf(this.variant);
			} catch (IllegalArgumentException e) {
				throw ApiException.badRequest("Unsupported size/variant in stats sync: " + this.size + "/" + this.variant);
			}
			if (this.difficulty < 1 || this.difficulty > Difficulty.LISA.index()) {
				throw ApiException.badRequest("difficulty must be between 1 and " + Difficulty.LISA.index() + ", got: " + this.difficulty);
			}
			if (this.gamesPlayed < 0 || this.solved < 0 || this.failed < 0 || this.totalTimeMs < 0 || this.hintsUsed < 0) {
				throw ApiException.badRequest("stats sync counters must not be negative");
			}
			if (this.solved + this.failed > this.gamesPlayed) {
				throw ApiException.badRequest("stats sync reports more outcomes than games played");
			}
		}
	}
}
