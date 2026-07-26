package net.luis.sudoku.repository;

import net.luis.sudoku.domain.DailyOutcome;
import net.luis.sudoku.domain.DailyResult;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;

import java.sql.*;
import java.time.LocalDate;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code daily_results} (server-spec 8.2).
 */
public final class DailyResultRepository {
	
	private static final String COLUMNS =
		"id, user_id, date, difficulty, attempt_no, outcome, elapsed_ms, mistakes, hints_used, verified, created_at";
	
	static @NonNull DailyResult map(@NonNull ResultSet result) throws SQLException {
		return new DailyResult(
			result.getLong("id"),
			result.getObject("user_id", UUID.class),
			result.getObject("date", LocalDate.class),
			result.getInt("difficulty"),
			result.getInt("attempt_no"),
			DailyOutcome.valueOf(result.getString("outcome")),
			result.getLong("elapsed_ms"),
			result.getInt("mistakes"),
			result.getInt("hints_used"),
			result.getBoolean("verified"),
			result.getTimestamp("created_at").toInstant()
		);
	}
	
	/**
	 * @return true if a {@code SOLVED} result already exists, which locks the date (spec 8.3)
	 */
	public boolean hasSolved(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull LocalDate date) throws SqlException {
		return transaction.from(DAILY_RESULTS).select()
			.where(Sql.equalTo(RESULT_USER_ID, userId))
			.where(Sql.equalTo(RESULT_DATE, date))
			.where(Sql.equalTo(RESULT_OUTCOME, DailyOutcome.SOLVED))
			.exists();
	}
	
	/**
	 * @return the next attempt number for this player, date and tier, starting at 1
	 */
	public int nextAttemptNo(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull LocalDate date,
	                         int difficulty) throws SqlException {
		Integer max = transaction.from(DAILY_RESULTS).select(Sql.max(RESULT_ATTEMPT_NO))
			.where(Sql.equalTo(RESULT_USER_ID, userId))
			.where(Sql.equalTo(RESULT_DATE, date))
			.where(Sql.equalTo(RESULT_DIFFICULTY, difficulty))
			.fetchOneOrNull();
		return (max == null ? 0 : max) + 1;
	}
	
	/**
	 * {@code id} is a DB-generated identity column the query builder's entity insert has no way to omit,
	 * so this stays raw SQL and relies on {@code RETURNING} to read the generated id (and DB-defaulted
	 * {@code created_at}) back, same carve-out as {@code currency_ledger}.
	 */
	public @NonNull DailyResult insert(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull LocalDate date,
	                                   int difficulty, int attemptNo, @NonNull DailyOutcome outcome, long elapsedMs,
	                                   int mistakes, int hintsUsed, boolean verified) throws SqlException {
		String sql = """
			INSERT INTO daily_results (user_id, date, difficulty, attempt_no, outcome, elapsed_ms, mistakes, hints_used, verified)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			RETURNING """ + " " + COLUMNS;
		try (PreparedStatement statement = transaction.getConnection().prepareStatement(sql)) {
			statement.setObject(1, userId);
			statement.setObject(2, date);
			statement.setInt(3, difficulty);
			statement.setInt(4, attemptNo);
			statement.setString(5, outcome.name());
			statement.setLong(6, elapsedMs);
			statement.setInt(7, mistakes);
			statement.setInt(8, hintsUsed);
			statement.setBoolean(9, verified);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return map(result);
			}
		} catch (SQLException e) {
			throw new SqlException("Failed to insert daily result", e);
		}
	}
	
	/**
	 * Deletes results for dates strictly before {@code before}, once they have been folded into
	 * {@code stats} (spec 8.6). Daily results are not retained historically.
	 */
	public int pruneBefore(@NonNull SqlTransaction transaction, @NonNull LocalDate before) throws SqlException {
		return transaction.from(DAILY_RESULTS).delete().where(Sql.lessThan(RESULT_DATE, before)).execute();
	}
}
