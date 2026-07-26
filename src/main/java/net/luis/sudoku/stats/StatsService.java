package net.luis.sudoku.stats;

import net.luis.sudoku.config.ServerConfig;
import net.luis.sudoku.db.AdvisoryLocks;
import net.luis.sudoku.db.Database;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.repository.*;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Statistics storage, the offline-to-online sync, and the daily rollover job (server-spec 9).
 */
public final class StatsService {
	
	private static final Logger log = LoggerFactory.getLogger(StatsService.class);
	
	/** Guards against a client uploading an implausible history in one call. */
	private static final int MAX_SYNC_ENTRIES = 200;
	
	private final Database database;
	private final StatsRepository stats;
	private final UserRepository users;
	private final StreakRepository streaks;
	private final DailyResultRepository dailyResults;
	private final DailyLeaderboardRepository leaderboard;
	private final ServerConfig config;
	private final Clock clock;
	
	public StatsService(@NonNull Database database, @NonNull StatsRepository stats, @NonNull UserRepository users,
	                    @NonNull StreakRepository streaks, @NonNull DailyResultRepository dailyResults,
	                    @NonNull DailyLeaderboardRepository leaderboard, @NonNull ServerConfig config,
	                    @NonNull Clock clock) {
		this.database = database;
		this.stats = stats;
		this.users = users;
		this.streaks = streaks;
		this.dailyResults = dailyResults;
		this.leaderboard = leaderboard;
		this.config = config;
		this.clock = clock;
	}
	
	private static @Nullable String lastSeen(@NonNull SqlTransaction connection, @NonNull UUID userId) throws SqlException {
		String sql = "SELECT max(last_seen_at) FROM devices WHERE user_id = ?";
		try (PreparedStatement statement = connection.getConnection().prepareStatement(sql)) {
			statement.setObject(1, userId);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				java.sql.Timestamp seen = result.getTimestamp(1);
				return seen == null ? null : seen.toInstant().toString();
			}
		} catch (SQLException e) {
			throw new SqlException("Failed to read last-seen timestamp", e);
		}
	}
	
	public @NonNull List<StatsEntry> forUser(@NonNull UUID userId) {
		return this.database.read(connection -> this.stats.findByUser(connection, userId));
	}
	
	/**
	 * @return every non-revoked player, with the summary shown in the player browser (spec 9)
	 */
	public @NonNull List<PlayerSummary> players() {
		return this.database.read(connection -> {
			List<PlayerSummary> summaries = new java.util.ArrayList<>();
			for (User user : this.users.findAll(connection)) {
				if (user.revoked()) {
					continue;
				}
				summaries.add(new PlayerSummary(
					user.id(),
					user.displayName(),
					user.role().name(),
					this.streaks.find(connection, user.id()).current(),
					lastSeen(connection, user.id())
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
	public void record(@NonNull SqlTransaction connection, @NonNull UUID userId, @NonNull GridSize size,
	                   @NonNull Variant variant, int difficulty, boolean solved, long elapsedMs, int hintsUsed)
		throws SqlException {
		this.stats.record(connection, userId, size.n(), variant.name(), difficulty, solved, elapsedMs, hintsUsed);
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
			int folded;
			try {
				Database.advisoryTransactionLock(connection.getConnection(), AdvisoryLocks.DAILY_ROLLOVER);
				
				folded = 0;
				String sql = """
					SELECT user_id, difficulty, outcome, elapsed_ms, hints_used
					  FROM daily_results
					 WHERE date < ? AND verified
					""";
				try (PreparedStatement statement = connection.getConnection().prepareStatement(sql)) {
					statement.setObject(1, today);
					try (ResultSet result = statement.executeQuery()) {
						while (result.next()) {
							boolean solved = "SOLVED".equals(result.getString("outcome"));
							this.stats.record(connection, result.getObject("user_id", UUID.class),
								this.config.dailySize().n(), this.config.dailyVariant().name(),
								result.getInt("difficulty"), solved, result.getLong("elapsed_ms"),
								result.getInt("hints_used"));
							folded++;
						}
					}
				}
			} catch (SQLException e) {
				throw new SqlException("Failed to run stats rollover", e);
			}
			
			// Prune only after the fold, and in the same transaction, so a crash between the two cannot
			// lose results (spec 8.6).
			this.dailyResults.pruneBefore(connection, today);
			this.leaderboard.pruneBefore(connection, today);
			
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
	public record PlayerSummary(@NonNull UUID id, @NonNull String displayName, @NonNull String role, int streak,
	                            @Nullable String lastSeenAt) {}
	
	/**
	 * One uploaded aggregate from a client's local history.
	 *
	 * @param size grid edge length
	 * @param variant {@code CLASSIC} or {@code CHAOS}
	 * @param difficulty tier index 1-6; Lisa is allowed here because it is a real single-player tier
	 * @param gamesPlayed games finished
	 * @param solved successes
	 * @param failed failures
	 * @param bestTimeMs fastest solve, or null
	 * @param totalTimeMs summed solve time
	 * @param hintsUsed hints consumed
	 */
	public record SyncEntry(int size, @NonNull String variant, int difficulty, int gamesPlayed, int solved, int failed,
	                        @Nullable Long bestTimeMs, long totalTimeMs, int hintsUsed) {
		
		void validate() {
			try {
				GridSize.ofEdgeLength(this.size);
				Variant.valueOf(this.variant);
			} catch (IllegalArgumentException e) {
				throw ApiException.badRequest("Unsupported size/variant in stats sync: " + this.size + "/" + this.variant);
			}
			if (this.difficulty < 1 || this.difficulty > 6) {
				throw ApiException.badRequest("difficulty must be between 1 and 6, got: " + this.difficulty);
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
