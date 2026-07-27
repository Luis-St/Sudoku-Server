package net.luis.sudoku.repository;

import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.query.row.SqlRow4;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code daily_leaderboard} (server-spec 8.6).
 * <p>
 * Ranked within a single difficulty tier only: players choose their own tier, so cross-tier comparison
 * would be meaningless. Hints used are recorded but never exposed in the ranking.
 */
public final class DailyLeaderboardRepository {
	
	/**
	 * Records a successful attempt, keeping the player's best time for the day.
	 * <p>
	 * The upsert has a composite conflict key and a {@code least()} merge on one column, neither of
	 * which the query builder's generic upsert supports (single conflict column, always
	 * {@code col = EXCLUDED.col}), so this stays raw SQL.
	 */
	public void record(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull LocalDate date, int difficulty, long elapsedMs, int attempts, int hintsUsed) throws SqlException {
		String sql = """
			INSERT INTO daily_leaderboard (date, difficulty, user_id, elapsed_ms, attempts, hints_used)
			VALUES (?, ?, ?, ?, ?, ?)
			ON CONFLICT (date, difficulty, user_id) DO UPDATE
			   SET elapsed_ms = least(daily_leaderboard.elapsed_ms, EXCLUDED.elapsed_ms),
			       attempts = EXCLUDED.attempts,
			       hints_used = EXCLUDED.hints_used
			""";
		try (PreparedStatement statement = transaction.getConnection().prepareStatement(sql)) {
			statement.setObject(1, date);
			statement.setInt(2, difficulty);
			statement.setObject(3, userId);
			statement.setLong(4, elapsedMs);
			statement.setInt(5, attempts);
			statement.setInt(6, hintsUsed);
			statement.executeUpdate();
		} catch (SQLException e) {
			throw new SqlException("Failed to record leaderboard entry", e);
		}
	}
	
	/**
	 * @return the day's ranking for one tier, fastest first; unverified results never reach this table
	 */
	public @NonNull List<Entry> ranking(@NonNull SqlTransaction transaction, @NonNull LocalDate date, int difficulty) throws SqlException {
		List<SqlRow4<UUID, String, Long, Integer>> rows = transaction.from(DAILY_LEADERBOARD)
			.select(LEADERBOARD_USER_ID, USER_DISPLAY_NAME, LEADERBOARD_ELAPSED_MS, LEADERBOARD_ATTEMPTS)
			.innerJoin(USERS, Sql.equalTo(USER_ID, LEADERBOARD_USER_ID))
			.where(Sql.equalTo(LEADERBOARD_DATE, date))
			.where(Sql.equalTo(LEADERBOARD_DIFFICULTY, difficulty))
			.where(Sql.equalTo(USER_REVOKED, false))
			.orderBy(LEADERBOARD_ELAPSED_MS.ascending(), LEADERBOARD_ATTEMPTS.ascending())
			.fetch();
		
		List<Entry> entries = new ArrayList<>(rows.size());
		for (SqlRow4<UUID, String, Long, Integer> row : rows) {
			entries.add(new Entry(row.first(), row.second(), row.third(), row.fourth()));
		}
		return entries;
	}
	
	/**
	 * Prunes rows for dates strictly before {@code before}, once folded into {@code stats}.
	 */
	public int pruneBefore(@NonNull SqlTransaction transaction, @NonNull LocalDate before) throws SqlException {
		return transaction.from(DAILY_LEADERBOARD).delete().where(Sql.lessThan(LEADERBOARD_DATE, before)).execute();
	}
	
	/**
	 * One ranked player. Hints are deliberately absent (spec 8.6).
	 *
	 * @param userId the player
	 * @param displayName their name
	 * @param elapsedMs their time
	 * @param attempts which attempt succeeded
	 */
	public record Entry(@NonNull UUID userId, @NonNull String displayName, long elapsedMs, int attempts) {}
}
