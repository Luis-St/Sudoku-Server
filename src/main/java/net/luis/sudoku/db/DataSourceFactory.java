package net.luis.sudoku.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.luis.sudoku.config.DatabaseConfig;
import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.dialect.SqlDialects;
import net.luis.utils.io.database.exception.database.SqlConnectionException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Builds the HikariCP pool and waits for Postgres to become reachable.
 * <p>
 * Startup ordering (server-spec 14): in a Compose deployment the database may still be starting when
 * the server boots, so the initial connection is retried with backoff rather than treated as fatal.
 * Once the pool is up, ordinary connection loss is Hikari's problem, not ours.
 */
public final class DataSourceFactory {
	
	private static final Logger log = LoggerFactory.getLogger(DataSourceFactory.class);
	
	private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
	private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
	private static final Duration MAX_WAIT = Duration.ofMinutes(5);
	
	private DataSourceFactory() {}
	
	/**
	 * Creates the pool and blocks until a connection can be established, or until {@link #MAX_WAIT}
	 * elapses.
	 *
	 * @throws DatabaseException if the database never became reachable
	 */
	/**
	 * Builds the pool, waits for Postgres, and wraps it in a LUtils {@link SqlDatabase} on the Postgres
	 * dialect.
	 * <p>
	 * The dialect is what renders every query the repositories build, so it is fixed here once rather
	 * than passed around.
	 */
	public static @NonNull SqlDatabase createDatabase(@NonNull DatabaseConfig config) {
		HikariDataSource dataSource = create(config);
		
		try {
			return SqlDatabase.builder(dataSource, SqlDialects.POSTGRESQL).autoCloseDataSource(true).build();
		} catch (SqlConnectionException e) {
			dataSource.close();
			throw new DatabaseException("Failed to open the database on " + config.safeUrl(), e);
		}
	}
	
	public static @NonNull HikariDataSource create(@NonNull DatabaseConfig config) {
		HikariConfig hikari = new HikariConfig();
		hikari.setJdbcUrl(config.url());
		hikari.setUsername(config.user());
		hikari.setPassword(config.password());
		hikari.setMaximumPoolSize(config.poolSize());
		hikari.setPoolName("sudoku-pool");
		// The pool is created eagerly but must not fail the constructor while Postgres is still booting;
		// awaitConnection below owns the retry policy instead.
		hikari.setInitializationFailTimeout(-1);
		hikari.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
		
		HikariDataSource dataSource = new HikariDataSource(hikari);
		try {
			awaitConnection(dataSource, config);
		} catch (RuntimeException e) {
			dataSource.close();
			throw e;
		}
		
		log.info("Connected to database {} (pool size {})", config.safeUrl(), config.poolSize());
		return dataSource;
	}
	
	private static void awaitConnection(@NonNull DataSource dataSource, @NonNull DatabaseConfig config) {
		long deadline = System.nanoTime() + MAX_WAIT.toNanos();
		Duration backoff = INITIAL_BACKOFF;
		SQLException last = null;
		
		while (System.nanoTime() < deadline) {
			try (Connection connection = dataSource.getConnection()) {
				if (connection.isValid((int) Duration.ofSeconds(5).toSeconds())) {
					return;
				}
			} catch (SQLException e) {
				last = e;
			}
			
			log.warn("Database {} not reachable yet, retrying in {}s", config.safeUrl(), backoff.toSeconds());
			
			try {
				Thread.sleep(backoff);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new DatabaseException("Interrupted while waiting for the database", e);
			}
			
			backoff = min(backoff.multipliedBy(2), MAX_BACKOFF);
		}
		throw new DatabaseException("Database " + config.safeUrl() + " did not become reachable within " + MAX_WAIT, last);
	}
	
	private static @NonNull Duration min(@NonNull Duration a, @NonNull Duration b) {
		return a.compareTo(b) <= 0 ? a : b;
	}
}
