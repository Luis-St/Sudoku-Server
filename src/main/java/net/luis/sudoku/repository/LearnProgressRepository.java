package net.luis.sudoku.repository;

import net.luis.sudoku.db.schema.Schema.LearnProgressRow;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.SqlConnectionSource;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.query.crud.SqlInsertQuery;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code learn_progress}, one row per finished learn exercise.
 * <p>
 * The learn area is local first, so this table is a copy of what a device already holds. What it buys is
 * the second device: the same account on a tablet sees the techniques it has mastered rather than an empty
 * wiki.
 */
public final class LearnProgressRepository {

	/**
	 * The state of an exercise finished using the technique it teaches, which is the only state that can
	 * overwrite another.
	 */
	public static final String SOLVED = "SOLVED";

	/**
	 * The state of an exercise finished without using the technique. It counts as done and earns nothing.
	 */
	public static final String PARTIAL = "PARTIAL";

	public @NonNull List<LearnProgressRow> forUser(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		return transaction.from(LEARN_PROGRESS).select().where(Sql.equalTo(LEARN_USER_ID, userId)).fetch();
	}

	/**
	 * Takes on what a device reports, keeping whichever of the two states is further along.
	 * <p>
	 * <strong>By better state, never by newer row.</strong> Both devices of an account are allowed to work
	 * offline for as long as they like, so "newest wins" would let a stale {@code PARTIAL} land on top of a
	 * {@code SOLVED} and silently un-earn an achievement the player has already been shown. A solve is a
	 * fact about something the player did; nothing that happened later makes it untrue.
	 *
	 * @param rows what the device holds
	 * @return how many rows the server actually took on, which is what the response reports
	 */
	public int merge(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull List<LearnProgressRow> rows) throws SqlException {
		if (rows.isEmpty()) {
			return 0;
		}

		List<LearnProgressRow> existing = this.forUser(transaction, userId);
		List<LearnProgressRow> better = rows.stream()
			.filter(row -> isBetter(row, find(existing, row)))
			.toList();
		if (better.isEmpty()) {
			return 0;
		}

		SqlInsertQuery.upsert(LEARN_PROGRESS, transaction.getDialect(), SqlConnectionSource.fixed(transaction.getConnection()),
			transaction.getQueryTimeout(), resultSet -> null, better, List.of(LEARN_USER_ID, LEARN_TECHNIQUE, LEARN_LEVEL, LEARN_SUB_LEVEL)).execute();
		return better.size();
	}

	/**
	 * Drops every row of one technique, which is what a client's per-technique reset syncs.
	 * <p>
	 * A reset is the one thing that may take a solve away, because it is the player asking for exactly
	 * that. It is scoped to a technique for the same reason it is on the client: a control that clears
	 * forty-one of them is a control that eventually clears the wrong one.
	 */
	public int reset(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull String technique) throws SqlException {
		return transaction.from(LEARN_PROGRESS).delete()
			.where(Sql.equalTo(LEARN_USER_ID, userId))
			.where(Sql.equalTo(LEARN_TECHNIQUE, technique))
			.execute();
	}

	/**
	 * Whether an incoming row is worth writing over what is already there.
	 * <p>
	 * A row that is not there yet always is; one that is only counts when it upgrades a partial to a solve.
	 * Everything else is either the same state again or a downgrade, and neither is worth a write.
	 */
	private static boolean isBetter(@NonNull LearnProgressRow incoming, @Nullable LearnProgressRow current) {
		if (current == null) {
			return true;
		}
		return !SOLVED.equals(current.state()) && SOLVED.equals(incoming.state());
	}

	private static @Nullable LearnProgressRow find(@NonNull List<LearnProgressRow> rows, @NonNull LearnProgressRow row) {
		return rows.stream()
			.filter(candidate -> candidate.technique().equals(row.technique()) && candidate.level() == row.level() && candidate.subLevel() == row.subLevel())
			.findFirst()
			.orElse(null);
	}
}
