package net.luis.sudoku.repository;

import net.luis.sudoku.domain.Streak;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.SqlConnectionSource;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.query.crud.SqlInsertQuery;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code streaks} (server-spec 8.3).
 */
public final class StreakRepository {
	
	public @NonNull Streak find(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		Streak found = transaction.from(STREAKS).select().where(Sql.equalTo(STREAK_USER_ID, userId)).fetchOneOrNull();
		return found == null ? Streak.none(userId) : found;
	}
	
	/**
	 * Upserts the whole row: every non-key column always moves to the given value, which matches how
	 * {@link Streak#completedOn} already folds in the previous state before this is called.
	 */
	public void save(@NonNull SqlTransaction transaction, @NonNull Streak streak) throws SqlException {
		SqlInsertQuery.upsert(STREAKS, transaction.getDialect(), SqlConnectionSource.fixed(transaction.getConnection()), transaction.getQueryTimeout(), resultSet -> null, List.of(streak), STREAK_USER_ID).execute();
	}
	
	/**
	 * @return the streak, locked for the rest of the transaction so a concurrent submission cannot
	 *   double-increment it
	 */
	public @NonNull Streak findForUpdate(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		Streak found = transaction.from(STREAKS).select().where(Sql.equalTo(STREAK_USER_ID, userId)).forUpdate().fetchOneOrNull();
		return found == null ? Streak.none(userId) : found;
	}
	
}
