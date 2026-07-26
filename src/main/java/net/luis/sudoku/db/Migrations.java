package net.luis.sudoku.db;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;

/**
 * Applies forward-only numbered SQL scripts at startup (server-spec 5.1, 14).
 * <p>
 * <strong>Why this is not LUtils' {@code SqlMigrationRunner}.</strong> That runner snapshots the schema
 * it creates by reading it back through {@code SqlJdbcTypeMapper}. On Postgres, LUtils' own dialect
 * renders {@code UUID} columns as a native {@code uuid}, which pgjdbc reports as JDBC {@code OTHER}
 * (1111) - a code the mapper has no case for. It therefore throws while introspecting the schema it
 * just built, and rolls the whole migration back. Every table here has a UUID column, so the runner
 * cannot be used until that is fixed in LUtils.
 * <p>
 * This is a narrow carve-out: DDL only. Connection pooling, transactions, dialect handling and queries
 * all still go through LUtils' {@link net.luis.utils.io.database.SqlDatabase}.
 * <p>
 * The applied version lives in {@code server_meta['schema_version']}, and the whole run happens under a
 * {@code pg_advisory_lock} so two containers restarting at once cannot migrate concurrently. Each
 * script runs in its own transaction together with the version bump, so a failure leaves the schema at
 * the last fully-applied version rather than half-migrated.
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
	public static final String SCHEMA_VERSION_KEY = "schema_version";
	
	private Migrations() {}
	
	/**
	 * Brings the schema up to date, doing nothing if it already is.
	 *
	 * @return the schema version after this run
	 */
	public static int migrate(@NonNull Database database) {
		return database.withConnection(connection -> {
			lock(connection);
			try {
				ensureServerMetaExists(connection);
				int current = readSchemaVersion(connection);
				int applied = applyPending(connection, current);
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
		return database.withConnection(connection -> {
			ensureServerMetaExists(connection);
			return readSchemaVersion(connection);
		});
	}
	
	private static int applyPending(@NonNull Connection connection, int current) throws SQLException {
		int version = current;
		for (Script script : SCRIPTS) {
			if (script.version() <= version) {
				continue;
			}
			if (script.version() != version + 1) {
				throw new DatabaseException("Migration gap: schema is at version " + version
					+ " but the next available script is V" + script.version());
			}
			apply(connection, script);
			version = script.version();
		}
		return version;
	}
	
	private static void apply(@NonNull Connection connection, @NonNull Script script) throws SQLException {
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
			writeSchemaVersion(connection, script.version());
			connection.commit();
			log.info("Applied migration V{}", script.version());
		} catch (SQLException e) {
			connection.rollback();
			throw new DatabaseException("Migration V" + script.version() + " (" + script.name() + ") failed", e);
		} finally {
			connection.setAutoCommit(autoCommit);
		}
	}
	
	/**
	 * {@code server_meta} is created here rather than only in V1, because the version has to be read
	 * out of it before any migration has run.
	 */
	private static void ensureServerMetaExists(@NonNull Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE IF NOT EXISTS server_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
		}
	}
	
	private static int readSchemaVersion(@NonNull Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM server_meta WHERE key = ?")) {
			statement.setString(1, SCHEMA_VERSION_KEY);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return 0;
				}
				String value = result.getString(1);
				try {
					return Integer.parseInt(value);
				} catch (NumberFormatException e) {
					throw new DatabaseException("server_meta['" + SCHEMA_VERSION_KEY + "'] is not a number: " + value, e);
				}
			}
		}
	}
	
	private static void writeSchemaVersion(@NonNull Connection connection, int version) throws SQLException {
		String sql = "INSERT INTO server_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, SCHEMA_VERSION_KEY);
			statement.setString(2, Integer.toString(version));
			statement.executeUpdate();
		}
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
