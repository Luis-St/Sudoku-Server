package net.luis.sudoku.repository;

import net.luis.sudoku.domain.StatsEntry;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;

import java.sql.*;
import java.util.List;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code stats}, aggregated per {@code (user, size, variant, difficulty)}
 * (server-spec 9).
 * <p>
 * Solve times are only meaningful within a difficulty tier, which is why the tier is part of the key
 * rather than something rolled up.
 */
public final class StatsRepository {
	
	/**
	 * Folds one finished game into the aggregate.
	 * <p>
	 * The merge is incremental ({@code games_played + 1}, {@code total_time_ms + EXCLUDED...}) with a
	 * null-aware {@code least()} for the best time, which the query builder's generic upsert cannot
	 * express (it only ever assigns {@code col = EXCLUDED.col}), so this stays raw SQL like
	 * {@code daily_leaderboard}'s write path.
	 */
	public void record(@NonNull SqlTransaction transaction, @NonNull UUID userId, int size, @NonNull String variant,
	                   int difficulty, boolean solved, long elapsedMs, int hintsUsed) throws SqlException {
		String sql = """
			INSERT INTO stats (user_id, size, variant, difficulty, games_played, solved, failed, best_time_ms,
			                   total_time_ms, hints_used)
			VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
			ON CONFLICT (user_id, size, variant, difficulty) DO UPDATE
			   SET games_played  = stats.games_played + 1,
			       solved        = stats.solved + EXCLUDED.solved,
			       failed        = stats.failed + EXCLUDED.failed,
			       best_time_ms  = CASE
			                         WHEN EXCLUDED.best_time_ms IS NULL THEN stats.best_time_ms
			                         WHEN stats.best_time_ms IS NULL THEN EXCLUDED.best_time_ms
			                         ELSE least(stats.best_time_ms, EXCLUDED.best_time_ms)
			                       END,
			       total_time_ms = stats.total_time_ms + EXCLUDED.total_time_ms,
			       hints_used    = stats.hints_used + EXCLUDED.hints_used
			""";
		try (PreparedStatement statement = transaction.getConnection().prepareStatement(sql)) {
			statement.setObject(1, userId);
			statement.setInt(2, size);
			statement.setString(3, variant);
			statement.setInt(4, difficulty);
			statement.setInt(5, solved ? 1 : 0);
			statement.setInt(6, solved ? 0 : 1);
			// Only a solve contributes a time; a failure has no meaningful duration to rank.
			if (solved) {
				statement.setLong(7, elapsedMs);
				statement.setLong(8, elapsedMs);
			} else {
				statement.setNull(7, Types.BIGINT);
				statement.setLong(8, 0);
			}
			statement.setInt(9, hintsUsed);
			statement.executeUpdate();
		} catch (SQLException e) {
			throw new SqlException("Failed to record stats", e);
		}
	}
	
	/**
	 * Merges a batch of locally accumulated history in one statement per entry (spec 9). Raw SQL for the
	 * same reason as {@link #record}.
	 */
	public void merge(@NonNull SqlTransaction transaction, @NonNull UUID userId, int size, @NonNull String variant,
	                  int difficulty, int gamesPlayed, int solved, int failed, Long bestTimeMs, long totalTimeMs,
	                  int hintsUsed) throws SqlException {
		String sql = """
			INSERT INTO stats (user_id, size, variant, difficulty, games_played, solved, failed, best_time_ms,
			                   total_time_ms, hints_used)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (user_id, size, variant, difficulty) DO UPDATE
			   SET games_played  = stats.games_played + EXCLUDED.games_played,
			       solved        = stats.solved + EXCLUDED.solved,
			       failed        = stats.failed + EXCLUDED.failed,
			       best_time_ms  = CASE
			                         WHEN EXCLUDED.best_time_ms IS NULL THEN stats.best_time_ms
			                         WHEN stats.best_time_ms IS NULL THEN EXCLUDED.best_time_ms
			                         ELSE least(stats.best_time_ms, EXCLUDED.best_time_ms)
			                       END,
			       total_time_ms = stats.total_time_ms + EXCLUDED.total_time_ms,
			       hints_used    = stats.hints_used + EXCLUDED.hints_used
			""";
		try (PreparedStatement statement = transaction.getConnection().prepareStatement(sql)) {
			statement.setObject(1, userId);
			statement.setInt(2, size);
			statement.setString(3, variant);
			statement.setInt(4, difficulty);
			statement.setInt(5, gamesPlayed);
			statement.setInt(6, solved);
			statement.setInt(7, failed);
			if (bestTimeMs == null) {
				statement.setNull(8, Types.BIGINT);
			} else {
				statement.setLong(8, bestTimeMs);
			}
			statement.setLong(9, totalTimeMs);
			statement.setInt(10, hintsUsed);
			statement.executeUpdate();
		} catch (SQLException e) {
			throw new SqlException("Failed to merge stats", e);
		}
	}
	
	public @NonNull List<StatsEntry> findByUser(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		return transaction.from(STATS).select().where(Sql.equalTo(STATS_USER_ID, userId))
			.orderBy(STATS_SIZE.ascending(), STATS_VARIANT.ascending(), STATS_DIFFICULTY.ascending())
			.fetch();
	}
	
	public int totalGamesPlayed(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		return transaction.from(STATS).select(Sql.sum(STATS_GAMES_PLAYED)).where(Sql.equalTo(STATS_USER_ID, userId))
			.fetchOneOrNull() instanceof Integer sum ? sum : 0;
	}
}
