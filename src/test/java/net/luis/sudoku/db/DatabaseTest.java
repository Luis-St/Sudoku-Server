package net.luis.sudoku.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.luis.sudoku.support.PostgresTest;
import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.dialect.SqlDialects;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link Database}.
 */
class DatabaseTest extends PostgresTest {

	@Test
	void isReachable_poolServesConnections_isTrue() {
		assertTrue(database.isReachable());
	}

	/**
	 * The whole point of the probe: a dead pool has to come back as {@code false} rather than as an
	 * exception, because {@code /health} is what decides whether the deployment gets restarted and a probe
	 * that throws would turn that into a 500 with no status in it.
	 */
	@Test
	void isReachable_poolIsClosed_isFalseRatherThanThrowing() throws Exception {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(dataSource().getJdbcUrl());
		config.setUsername(dataSource().getUsername());
		config.setPassword(dataSource().getPassword());
		config.setMaximumPoolSize(1);

		HikariDataSource pool = new HikariDataSource(config);
		SqlDatabase sqlDatabase = SqlDatabase.builder(pool, SqlDialects.POSTGRESQL).build();
		Database dying = new Database(sqlDatabase);
		assertTrue(dying.isReachable());

		pool.close();
		assertFalse(dying.isReachable());
	}
}
