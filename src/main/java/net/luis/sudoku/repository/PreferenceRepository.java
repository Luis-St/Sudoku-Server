package net.luis.sudoku.repository;

import net.luis.sudoku.db.schema.Schema;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.SqlConnectionSource;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.query.crud.SqlInsertQuery;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code daily_preferences} and {@code daily_assignments} (server-spec 8.1).
 */
public final class PreferenceRepository {
	
	/** Spec 8.1: the default tier when a player has never chosen one. */
	public static final int DEFAULT_DIFFICULTY = 3;
	
	public int dailyDifficulty(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		Schema.PreferenceRow row = transaction.from(DAILY_PREFERENCES).select()
			.where(Sql.equalTo(PREFERENCE_USER_ID, userId)).fetchOneOrNull();
		return row == null ? DEFAULT_DIFFICULTY : row.difficulty();
	}
	
	/**
	 * Every non-key column always moves to the given value, which matches what the query builder's
	 * generic upsert expresses.
	 */
	public void setDailyDifficulty(@NonNull SqlTransaction transaction, @NonNull UUID userId, int difficulty, @NonNull Instant now) throws SqlException {
		Schema.PreferenceRow draft = new Schema.PreferenceRow(userId, difficulty, now);
		SqlInsertQuery.upsert(DAILY_PREFERENCES, transaction.getDialect(), SqlConnectionSource.fixed(transaction.getConnection()),
			transaction.getQueryTimeout(), resultSet -> null, List.of(draft), PREFERENCE_USER_ID).execute();
	}
	
	/**
	 * Returns the difficulty already locked in for this date, or null if the day has not started for
	 * this player yet.
	 */
	public @Nullable Integer assignedDifficulty(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull LocalDate date) throws SqlException {
		Schema.AssignmentRow row = transaction.from(DAILY_ASSIGNMENTS).select()
			.where(Sql.equalTo(ASSIGNMENT_USER_ID, userId)).where(Sql.equalTo(ASSIGNMENT_DATE, date)).fetchOneOrNull();
		return row == null ? null : row.difficulty();
	}
	
	/**
	 * Locks a difficulty in for a date, keeping any value already recorded.
	 * <p>
	 * {@code DO NOTHING} is the whole mechanism behind "a change takes effect from the next day only":
	 * once a day has begun for a player, its tier is immutable, so switching preference mid-day cannot
	 * hand them an easier puzzle for today.
	 *
	 * @return the difficulty now in force for that date, which may be an earlier one
	 */
	public int assign(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull LocalDate date, int difficulty) throws SqlException {
		Schema.AssignmentRow draft = new Schema.AssignmentRow(userId, date, difficulty);
		transaction.from(DAILY_ASSIGNMENTS).insert(draft, ASSIGNMENT_USER_ID, ASSIGNMENT_DATE).execute();
		Integer assigned = this.assignedDifficulty(transaction, userId, date);
		return assigned == null ? difficulty : assigned;
	}
}
