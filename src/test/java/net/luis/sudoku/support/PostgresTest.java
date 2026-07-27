package net.luis.sudoku.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.luis.sudoku.db.Database;
import net.luis.sudoku.db.Migrations;
import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.dialect.SqlDialects;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.*;

/**
 * Base class for tests that need a real Postgres.
 * <p>
 * The invariants this server relies on - {@code pg_advisory_xact_lock}, conditional {@code UPDATE} row
 * counts, {@code SELECT ... FOR UPDATE}, {@code ON CONFLICT} - are all Postgres behaviour. An in-memory
 * substitute would let a broken implementation pass, so tests run against the real thing.
 * <p>
 * One container is shared by every subclass (started once, never stopped - Ryuk reaps it), with the
 * schema re-created between tests. Starting a container per class would dominate the runtime.
 */
public abstract class PostgresTest {
	
	private static PostgreSQLContainer<?> container;
	private static HikariDataSource dataSource;
	private static SqlDatabase sqlDatabase;
	
	protected static Database database;
	
	@BeforeAll
	static void startDatabase() throws net.luis.utils.io.database.exception.database.SqlConnectionException {
		if (container != null) {
			return;
		}
		container = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("sudoku")
			.withUsername("sudoku")
			.withPassword("test");
		container.start();
		
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(container.getJdbcUrl());
		config.setUsername(container.getUsername());
		config.setPassword(container.getPassword());
		// Room for the concurrency tests to hold several connections at once without deadlocking on
		// pool exhaustion rather than on the lock actually under test.
		config.setMaximumPoolSize(8);
		dataSource = new HikariDataSource(config);
		
		sqlDatabase = SqlDatabase.builder(dataSource, SqlDialects.POSTGRESQL).build();
		database = new Database(sqlDatabase);
	}
	
	protected static @NonNull SqlDatabase sqlDatabase() {
		return sqlDatabase;
	}
	
	protected static @NonNull HikariDataSource dataSource() {
		return dataSource;
	}
	
	/**
	 * Drops and re-applies the schema, so each test starts from an empty database.
	 * <p>
	 * The whole schema goes, including LUtils' own migration bookkeeping tables, so
	 * {@link Migrations#migrate} genuinely re-runs rather than reporting itself already applied.
	 */
	@BeforeEach
	void resetSchema() throws SQLException {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA public CASCADE");
			statement.execute("CREATE SCHEMA public");
		}
		Migrations.migrate(database);
	}
}
