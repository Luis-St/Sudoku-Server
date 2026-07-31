package net.luis.sudoku.repository;

import net.luis.utils.io.database.SqlConnectionSource;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.query.crud.SqlInsertQuery;
import net.luis.utils.io.database.query.util.SqlSetClause;
import net.luis.utils.io.database.table.SqlColumn;
import net.luis.utils.io.database.table.SqlTable;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Runs an {@code INSERT ... ON CONFLICT DO UPDATE} with custom set clauses.
 * <p>
 * The merging upserts this server needs ({@code stats}, {@code daily_leaderboard}) assign more than
 * {@code col = EXCLUDED.col}: they add the incoming value to the stored one and keep the smaller of two
 * times. That form is reachable only through {@link SqlInsertQuery#upsert}'s static overload rather than
 * {@code transaction.from(table).insert(...)}, and it takes the plumbing - dialect, connection source,
 * timeout - that the transaction otherwise passes on its own. This wraps that one call so a repository
 * reads as table, row, conflict key, merge rules and nothing else.
 */
final class Upserts {
	
	private Upserts() {}
	
	/**
	 * Inserts {@code row}, or applies {@code updateClauses} to the existing row when it collides on
	 * {@code conflictColumns}.
	 * <p>
	 * Reference the incoming row's value inside a clause with
	 * {@code column.of(SqlAlias.EXCLUDED)}, which renders as Postgres' {@code excluded} pseudo-relation.
	 *
	 * @param transaction The transaction to run in
	 * @param table The table to insert into
	 * @param row The row to insert
	 * @param conflictColumns The columns whose collision triggers the update
	 * @param updateClauses The assignments applied on collision
	 * @param <E> The row type of the table
	 * @throws SqlException If the statement could not be built or executed
	 */
	static <E> void upsert(
		@NonNull SqlTransaction transaction,
		@NonNull SqlTable<E> table,
		@NonNull E row,
		@NonNull List<SqlColumn<E, ?>> conflictColumns,
		@NonNull List<SqlSetClause<E, ?>> updateClauses
	) throws SqlException {
		SqlInsertQuery.upsert(
			table,
			transaction.getDialect(),
			// Fixed rather than pooled: the statement has to run on the transaction's own connection, or
			// it would neither see nor be covered by the transaction it belongs to.
			SqlConnectionSource.fixed(transaction.getConnection()),
			transaction.getQueryTimeout(),
			// Nothing is read back, so no row ever reaches this mapper.
			resultSet -> null,
			List.of(row),
			conflictColumns,
			updateClauses
		).execute();
	}
}
