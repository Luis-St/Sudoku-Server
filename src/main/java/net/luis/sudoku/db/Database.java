package net.luis.sudoku.db;

import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.*;

/**
 * The server's database access point, backed by LUtils' {@link SqlDatabase}.
 * <p>
 * LUtils owns connection handling, transaction demarcation, dialect rendering and migrations. This
 * class is the thin seam the rest of the server talks to, so services express intent - "do this in one
 * transaction" - without touching JDBC lifecycle themselves.
 * <p>
 * Repositories receive the {@link SqlTransaction} itself, not just its connection, so they can build
 * query-builder statements via {@link SqlTransaction#from(net.luis.utils.io.database.table.SqlTable)}.
 * Handing out the transaction (rather than a bare connection) is also what keeps a single transaction
 * spanning several repository calls, which is what the invariants in server-spec 5.1 require: the
 * bootstrap-admin claim, the last-admin check and stake escrow all read and write across more than one
 * table and must commit or roll back together.
 */
public final class Database implements AutoCloseable {
	
	private final SqlDatabase database;
	
	public Database(@NonNull SqlDatabase database) {
		this.database = database;
	}
	
	/**
	 * Takes a transaction-scoped advisory lock, released automatically at commit or rollback.
	 * <p>
	 * Used where a rule cannot be expressed as a constraint and row locking is not enough - notably
	 * "no non-revoked admin exists" during the bootstrap claim, and the last-admin invariant, where the
	 * check counts rows the mutation does not touch (server-spec 5.1, 6.3, 7.1).
	 * <p>
	 * Raw SQL on purpose: advisory locks are a Postgres-specific escape hatch with no portable
	 * equivalent, so they sit outside the dialect-rendered query layer by design.
	 */
	public static void advisoryTransactionLock(@NonNull Connection connection, long key) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
			statement.setLong(1, key);
			statement.execute();
		}
	}
	
	/**
	 * Surfaces the cause a caller actually cares about.
	 * <p>
	 * {@link SqlDatabase#inTransaction} wraps whatever the body threw, so an {@code ApiException} raised
	 * by a service - {@code LAST_ADMIN}, {@code INVITE_INVALID} - would otherwise reach the handler as an
	 * opaque SQL failure and become a 500 instead of its intended status.
	 */
	private static @NonNull RuntimeException unwrap(@NonNull SqlException e, @NonNull String message) {
		for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof RuntimeException runtime && !(cause instanceof SqlException)) {
				return runtime;
			}
		}
		return new DatabaseException(message, e);
	}
	
	/**
	 * @return the underlying LUtils database, for query-builder access and migrations
	 */
	public @NonNull SqlDatabase sql() {
		return this.database;
	}
	
	public @NonNull DataSource dataSource() {
		return this.database.getDataSource();
	}
	
	/**
	 * Runs {@code work} in a transaction, committing on normal return and rolling back on any throw.
	 */
	public <T> T transaction(@NonNull SqlFunction<T> work) {
		try {
			return this.database.inTransaction(work::apply);
		} catch (SqlException e) {
			throw unwrap(e, "Transaction failed");
		}
	}
	
	/**
	 * Runs {@code work} in a transaction, discarding any result.
	 * <p>
	 * Deliberately not an overload of {@link #transaction}: a lambda whose body is a single method call
	 * matches both shapes, so overloading would make every such call site ambiguous.
	 */
	public void execute(@NonNull SqlConsumer work) {
		this.transaction(transaction -> {
			work.accept(transaction);
			return null;
		});
	}
	
	/**
	 * Runs {@code work} against a read-only transaction.
	 * <p>
	 * Read-only is not just documentation: it lets Postgres skip work a writable transaction has to do,
	 * and it turns an accidental write in a query path into an error rather than a silent mutation.
	 */
	public <T> T read(@NonNull SqlFunction<T> work) {
		try (SqlTransaction transaction = this.database.beginReadOnlyTransaction()) {
			try {
				T result = work.apply(transaction);
				transaction.commit();
				return result;
			} catch (SqlException e) {
				transaction.rollback();
				throw unwrap(e, "Query failed");
			} catch (RuntimeException e) {
				transaction.rollback();
				throw e;
			}
		} catch (SqlException e) {
			throw unwrap(e, "Query failed");
		}
	}
	
	/**
	 * Borrows a raw pooled connection with autocommit left as the pool supplies it.
	 * <p>
	 * For work that must own its own commit boundaries rather than sit inside one - migrations, which
	 * take a session-scoped advisory lock and commit each script separately. Ordinary code wants
	 * {@link #transaction} or {@link #read} instead.
	 */
	public <T> T withConnection(@NonNull ConnectionFunction<T> work) {
		try (Connection connection = this.dataSource().getConnection()) {
			return work.apply(connection);
		} catch (SQLException e) {
			throw new DatabaseException("Connection work failed", e);
		}
	}
	
	@Override
	public void close() {
		try {
			this.database.close();
		} catch (SqlException e) {
			// Shutdown only; a pool that refuses to close cleanly must not mask the real exit path.
			throw new DatabaseException("Failed to close the database", e);
		}
	}
	
	/**
	 * Work run against a transaction, with query-builder access via {@link SqlTransaction#from(net.luis.utils.io.database.table.SqlTable)}.
	 */
	@FunctionalInterface
	public interface SqlFunction<T> {
		
		T apply(@NonNull SqlTransaction transaction) throws SqlException;
	}
	
	@FunctionalInterface
	public interface SqlConsumer {
		
		void accept(@NonNull SqlTransaction transaction) throws SqlException;
	}
	
	/**
	 * Work run against a raw pooled connection, for callers that must own their own commit boundaries.
	 */
	@FunctionalInterface
	public interface ConnectionFunction<T> {
		
		T apply(@NonNull Connection connection) throws SQLException;
	}
}
