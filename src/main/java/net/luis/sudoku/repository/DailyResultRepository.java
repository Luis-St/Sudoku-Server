package net.luis.sudoku.repository;

import net.luis.sudoku.domain.DailyOutcome;
import net.luis.sudoku.domain.DailyResult;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code daily_results} (server-spec 8.2).
 */
public final class DailyResultRepository {
	
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
	 * Inserts one attempt and reads the stored row back.
	 * <p>
	 * {@code id} is a DB-generated identity column, omitted from the value tuple by the query builder
	 * itself because the column is declared {@code autoIncrement()} - which is why the insert reads the
	 * row back rather than returning what it was handed. {@code createdAt} comes from the caller's clock,
	 * like every other timestamp the server writes, so a test clock and a fold-and-prune run agree on
	 * what day a row belongs to.
	 */
	public @NonNull DailyResult insert(
		@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull LocalDate date, int difficulty, int attemptNo, @NonNull DailyOutcome outcome, long elapsedMs, int mistakes, int hintsUsed, boolean verified, @NonNull Instant createdAt
	) throws SqlException {
		// The id passed here is never rendered - see above - so any value does.
		DailyResult row = new DailyResult(0L, userId, date, difficulty, attemptNo, outcome, elapsedMs, mistakes, hintsUsed, verified, createdAt);
		List<DailyResult> inserted = transaction.from(DAILY_RESULTS).insert(row).returning();
		if (inserted.isEmpty()) {
			throw new SqlException("Failed to insert daily result: no row returned");
		}
		return inserted.getFirst();
	}
	
	/**
	 * Deletes results for dates strictly before {@code before}, once they have been folded into
	 * {@code stats} (spec 8.6). Daily results are not retained historically.
	 */
	public int pruneBefore(@NonNull SqlTransaction transaction, @NonNull LocalDate before) throws SqlException {
		return transaction.from(DAILY_RESULTS).delete().where(Sql.lessThan(RESULT_DATE, before)).execute();
	}
}
