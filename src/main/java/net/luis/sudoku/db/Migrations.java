package net.luis.sudoku.db;

import net.luis.sudoku.db.schema.SchemaMigration;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.migration.SqlMigrationRunner;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

/**
 * Brings the database schema up to date at startup (server-spec 5.1, 14).
 * <p>
 * The schema itself is defined in {@link SchemaMigration} as LUtils migrations over
 * {@link net.luis.sudoku.db.schema.Schema}'s table definitions - there is no SQL text anywhere in the
 * migration path. LUtils' {@link SqlMigrationRunner} owns the bookkeeping: which versions are applied,
 * a checksum per version so an edited migration is caught rather than silently skipped, and a
 * transaction per migration, so a failure leaves the schema at the last fully applied version rather
 * than half-migrated.
 * <p>
 * What this class adds is the one thing the runner does not do: a session-scoped
 * {@code pg_advisory_lock} around the whole run, so two containers starting at once cannot migrate
 * concurrently. That lock is a Postgres procedural call with no {@code FROM} clause and therefore the
 * one statement here that is still hand-written - see {@link AdvisoryLocks} for the transaction-scoped
 * locks the services use.
 */
public final class Migrations {
	
	private static final Logger log = LoggerFactory.getLogger(Migrations.class);
	
	/**
	 * Arbitrary fixed key identifying the migration lock. Distinct from every key in
	 * {@link AdvisoryLocks}, which are transaction-scoped; this one is session-scoped.
	 */
	private static final long ADVISORY_LOCK_KEY = 8_101_975_204_311L;
	
	private Migrations() {}
	
	/**
	 * Brings the schema up to date, doing nothing if it already is.
	 *
	 * @param database The database to migrate
	 * @return the schema version after this run
	 */
	public static int migrate(@NonNull Database database) {
		return database.withConnection(connection -> {
			lock(connection);
			try {
				SqlMigrationRunner runner = SqlMigrationRunner.of(database.sql());
				runner.register(SchemaMigration.ALL);
				runner.migrate();
				log.info("Schema is up to date at version {}", SchemaMigration.CURRENT_VERSION);
				return SchemaMigration.CURRENT_VERSION;
			} catch (SqlException e) {
				throw new DatabaseException("Failed to migrate the schema", e);
			} finally {
				unlock(connection);
			}
		});
	}
	
	private static void lock(@NonNull Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
			statement.setLong(1, ADVISORY_LOCK_KEY);
			statement.execute();
		}
	}
	
	private static void unlock(@NonNull Connection connection) {
		try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
			statement.setLong(1, ADVISORY_LOCK_KEY);
			statement.execute();
		} catch (SQLException e) {
			// The lock is session-scoped, so returning the connection to the pool would hold it until the
			// connection is recycled. Worth a loud warning, but not worth failing a successful boot.
			log.warn("Failed to release the migration advisory lock", e);
		}
	}
}
