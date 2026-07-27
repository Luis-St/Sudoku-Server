package net.luis.sudoku.db;

import net.luis.sudoku.db.schema.Schema;
import net.luis.utils.io.database.*;
import net.luis.utils.io.database.dialect.SqlDialect;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.query.SqlQueryProvider;
import net.luis.utils.io.database.query.crud.SqlInsertQuery;
import net.luis.utils.io.database.table.SqlTableProvider;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.List;

/**
 * Applies forward-only numbered SQL scripts at startup (server-spec 5.1, 14).
 * <p>
 * <strong>Why the scripts themselves are not LUtils' {@code SqlMigrationRunner}.</strong> These are
 * hand-written, arbitrary SQL files ({@code resources/migrations/V*.sql}) - there is no builder API for
 * "run this opaque script", so executing them is necessarily raw JDBC regardless of the introspection
 * bug below. The {@code server_meta} bookkeeping around them (creating the table, reading/writing the
 * applied version) has no such excuse and now goes through the LUtils query builder instead.
 * <p>
 * LUtils' {@code SqlMigrationRunner} itself is still not used to apply the numbered scripts: it snapshots
 * the schema it creates by reading it back through {@code SqlJdbcTypeMapper}, which historically choked
 * on Postgres native {@code UUID} columns (fixed in LUtils 10.4.0-beta.3, see
 * {@code BUG_REPORT_io_database_postgres.md} in the LUtils repo) - but since these scripts were never
 * written as {@code SqlMigrationRunner} migrations in the first place, there is nothing to switch over.
 * <p>
 * This is a narrow carve-out: the two things a portable query builder genuinely cannot express -
 * arbitrary multi-statement DDL text, and the Postgres-specific {@code pg_advisory_lock}/{@code unlock}
 * pair (session-scoped, no portable equivalent - see {@link AdvisoryLocks} for the transaction-scoped
 * ones). Everything else here - creating {@code server_meta}, reading and writing the schema version - is
 * LUtils {@link SqlTableProvider}/query builder against {@link Schema#SERVER_META}.
 * <p>
 * The applied version lives in {@code server_meta['schema_version']}, and the whole run happens under a
 * {@code pg_advisory_lock} so two containers restarting at once cannot migrate concurrently. Each script
 * runs in its own transaction together with the version bump, so a failure leaves the schema at the last
 * fully-applied version rather than half-migrated.
 */
public final class Migrations {
	
	private static final Logger log = LoggerFactory.getLogger(Migrations.class);
	
	/**
	 * Arbitrary fixed key identifying the migration lock. Distinct from every key in
	 * {@link AdvisoryLocks}, which are transaction-scoped; this one is session-scoped.
	 */
	private static final long ADVISORY_LOCK_KEY = 8_101_975_204_311L;
	/**
	 * Every migration, in order. Append only - never renumber or edit a released script.
	 */
	private static final List<Script> SCRIPTS = List.of(
		new Script(1, "init"),
		new Script(2, "daily_preferences")
	);
	/**
	 * LUtils' {@link SqlDatabase} does not expose the query timeout it was built with (only the dialect,
	 * via {@link SqlDatabase#getDialect()}), so this matches {@code SqlDatabaseBuilder}'s own default -
	 * the server never overrides it in {@code DataSourceFactory}. If that ever changes, this must move
	 * in step.
	 */
	private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);
	public static final String SCHEMA_VERSION_KEY = "schema_version";
	
	private Migrations() {}
	
	/**
	 * Brings the schema up to date, doing nothing if it already is.
	 *
	 * @return the schema version after this run
	 */
	public static int migrate(@NonNull Database database) {
		SqlDialect dialect = database.sql().getDialect();
		return database.withConnection(connection -> {
			lock(connection);
			try {
				ensureServerMetaExists(dialect, connection);
				int current = readSchemaVersion(dialect, connection);
				int applied = applyPending(dialect, connection, current);
				if (applied == current) {
					log.info("Schema is up to date at version {}", current);
				}
				return applied;
			} finally {
				unlock(connection);
			}
		});
	}
	
	/**
	 * @return the current schema version without applying anything
	 */
	public static int currentVersion(@NonNull Database database) {
		SqlDialect dialect = database.sql().getDialect();
		return database.withConnection(connection -> {
			ensureServerMetaExists(dialect, connection);
			return readSchemaVersion(dialect, connection);
		});
	}
	
	private static int applyPending(@NonNull SqlDialect dialect, @NonNull Connection connection, int current) throws SQLException {
		int version = current;
		for (Script script : SCRIPTS) {
			if (script.version() <= version) {
				continue;
			}
			if (script.version() != version + 1) {
				throw new DatabaseException("Migration gap: schema is at version " + version + " but the next available script is V" + script.version());
			}
			
			apply(dialect, connection, script);
			version = script.version();
		}
		return version;
	}
	
	private static void apply(@NonNull SqlDialect dialect, @NonNull Connection connection, @NonNull Script script) throws SQLException {
		log.info("Applying migration V{} ({})", script.version(), script.name());
		String sql = script.read();
		
		boolean autoCommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try {
			try (Statement statement = connection.createStatement()) {
				// The Postgres driver accepts several statements in one execute, and none of this SQL
				// contains a semicolon inside a literal or a dollar-quoted body, so no splitter is needed.
				statement.execute(sql);
			}
			
			writeSchemaVersion(dialect, connection, script.version());
			connection.commit();
			log.info("Applied migration V{}", script.version());
		} catch (SQLException | SqlException e) {
			connection.rollback();
			throw new DatabaseException("Migration V" + script.version() + " (" + script.name() + ") failed", e);
		} finally {
			connection.setAutoCommit(autoCommit);
		}
	}
	
	/**
	 * {@code server_meta} is created here rather than only in V1, because the version has to be read
	 * out of it before any migration has run. {@link Schema#SERVER_META}'s columns are plain
	 * {@code TEXT}, matching V1's raw {@code CREATE TABLE} exactly, so it makes no difference which of
	 * the two idempotent statements a fresh database happens to run first.
	 */
	private static void ensureServerMetaExists(@NonNull SqlDialect dialect, @NonNull Connection connection) throws SQLException {
		try {
			new SqlTableProvider<>(Schema.SERVER_META, dialect, SqlConnectionSource.fixed(connection), QUERY_TIMEOUT).createIfNotExists();
		} catch (SqlException e) {
			throw new DatabaseException("Failed to ensure server_meta exists", e);
		}
	}
	
	private static int readSchemaVersion(@NonNull SqlDialect dialect, @NonNull Connection connection) throws SQLException {
		try {
			String value = new SqlQueryProvider<>(Schema.SERVER_META, dialect, SqlConnectionSource.fixed(connection), QUERY_TIMEOUT)
				.select(Schema.META_VALUE)
				.where(Sql.equalTo(Schema.META_KEY, SCHEMA_VERSION_KEY))
				.fetchOneOrNull();
			if (value == null) {
				return 0;
			}
			
			try {
				return Integer.parseInt(value);
			} catch (NumberFormatException e) {
				throw new DatabaseException("server_meta['" + SCHEMA_VERSION_KEY + "'] is not a number: " + value, e);
			}
		} catch (SqlException e) {
			throw new DatabaseException("Failed to read the schema version", e);
		}
	}
	
	private static void writeSchemaVersion(@NonNull SqlDialect dialect, @NonNull Connection connection, int version) throws SqlException {
		Schema.ServerMetaRow row = new Schema.ServerMetaRow(SCHEMA_VERSION_KEY, Integer.toString(version));
		SqlInsertQuery.upsert(Schema.SERVER_META, dialect, SqlConnectionSource.fixed(connection), QUERY_TIMEOUT, resultSet -> null, List.of(row), Schema.META_KEY).execute();
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
	
	/**
	 * One forward-only migration script.
	 *
	 * @param version the schema version this script produces; must be strictly increasing
	 * @param name descriptive name, forming the resource file name
	 */
	private record Script(int version, @NonNull String name) {
		
		private @NonNull String read() {
			String path = "migrations/V" + this.version + "__" + this.name + ".sql";
			try (InputStream stream = Migrations.class.getClassLoader().getResourceAsStream(path)) {
				if (stream == null) {
					throw new DatabaseException("Migration script not found on the classpath: " + path);
				}
				return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new DatabaseException("Failed to read migration script: " + path, e);
			}
		}
	}
}
