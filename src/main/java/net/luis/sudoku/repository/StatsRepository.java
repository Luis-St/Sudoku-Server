package net.luis.sudoku.repository;

import net.luis.sudoku.domain.StatsEntry;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.query.SqlAlias;
import net.luis.utils.io.database.query.util.SqlSetClause;
import net.luis.utils.io.database.query.util.SqlSetType;
import net.luis.utils.io.database.table.SqlColumn;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;

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
	 * How a colliding row is merged into the stored aggregate, shared by both write paths.
	 * <p>
	 * Every counter accumulates rather than being overwritten - that is the whole point of the table -
	 * and the best time keeps whichever of the two is smaller. Postgres' {@code least()} ignores nulls,
	 * so it already does the right thing both for a player's first solve (stored time still null) and
	 * for a failure that carries no time at all (incoming null).
	 */
	private static final List<SqlSetClause<StatsEntry, ?>> MERGE_CLAUSES = List.of(
		new SqlSetClause<>(STATS_GAMES_PLAYED, STATS_GAMES_PLAYED.of(SqlAlias.EXCLUDED), SqlSetType.INCREMENT),
		new SqlSetClause<>(STATS_SOLVED, STATS_SOLVED.of(SqlAlias.EXCLUDED), SqlSetType.INCREMENT),
		new SqlSetClause<>(STATS_FAILED, STATS_FAILED.of(SqlAlias.EXCLUDED), SqlSetType.INCREMENT),
		new SqlSetClause<>(STATS_BEST_TIME_MS, Sql.least(STATS_BEST_TIME_MS, STATS_BEST_TIME_MS.of(SqlAlias.EXCLUDED)), SqlSetType.EXPRESSION),
		new SqlSetClause<>(STATS_TOTAL_TIME_MS, STATS_TOTAL_TIME_MS.of(SqlAlias.EXCLUDED), SqlSetType.INCREMENT),
		new SqlSetClause<>(STATS_HINTS_USED, STATS_HINTS_USED.of(SqlAlias.EXCLUDED), SqlSetType.INCREMENT)
	);
	
	/** The aggregate's key: one row per player, size, variant and tier. */
	private static final List<SqlColumn<StatsEntry, ?>> KEY_COLUMNS = List.of(STATS_USER_ID, STATS_SIZE, STATS_VARIANT, STATS_DIFFICULTY);
	
	/**
	 * Folds one finished game into the aggregate.
	 */
	public void record(@NonNull SqlTransaction transaction, @NonNull UUID userId, int size, @NonNull String variant, int difficulty, boolean solved, long elapsedMs, int hintsUsed) throws SqlException {
		// Only a solve contributes a time; a failure has no meaningful duration to rank.
		StatsEntry entry = new StatsEntry(
			userId, size, variant, difficulty, 1, solved ? 1 : 0, solved ? 0 : 1, solved ? elapsedMs : null, solved ? elapsedMs : 0L, hintsUsed
		);
		Upserts.upsert(transaction, STATS, entry, KEY_COLUMNS, MERGE_CLAUSES);
	}
	
	/**
	 * Merges a batch of locally accumulated history in one statement per entry (spec 9).
	 */
	public void merge(
		@NonNull SqlTransaction transaction, @NonNull UUID userId, int size, @NonNull String variant, int difficulty, int gamesPlayed, int solved, int failed, Long bestTimeMs, long totalTimeMs, int hintsUsed
	) throws SqlException {
		StatsEntry entry = new StatsEntry(userId, size, variant, difficulty, gamesPlayed, solved, failed, bestTimeMs, totalTimeMs, hintsUsed);
		Upserts.upsert(transaction, STATS, entry, KEY_COLUMNS, MERGE_CLAUSES);
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
