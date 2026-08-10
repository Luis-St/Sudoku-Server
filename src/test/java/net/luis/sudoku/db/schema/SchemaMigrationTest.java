package net.luis.sudoku.db.schema;

import net.luis.sudoku.compat.LegacyDifficulty;
import net.luis.sudoku.db.Migrations;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.KeyAlgorithm;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.domain.Session;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.match.MatchMode;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.repository.DeviceRepository;
import net.luis.sudoku.repository.MatchRepository;
import net.luis.sudoku.repository.SessionRepository;
import net.luis.sudoku.repository.PreferenceRepository;
import net.luis.sudoku.repository.UserRepository;
import net.luis.sudoku.support.PostgresTest;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.migration.SqlMigration;
import net.luis.utils.io.database.migration.SqlMigrationRunner;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.MATCHES;
import static net.luis.sudoku.db.schema.Schema.MATCH_GIVENS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link SchemaMigration}, and specifically for the two migrations that a suite starting
 * from a fully migrated database cannot say anything about.
 * <p>
 * The rest of the suite proves nothing about the rescale: {@link PostgresTest} starts every test from a
 * database migrated all the way to {@link SchemaMigration#CURRENT_VERSION}, so the rescale runs against
 * empty tables and passes whether it works or not. These tests deliberately stop at the version
 * <em>before</em> it, write rows that mean what they meant then, and only then let it run - which is the
 * only arrangement in which it can be wrong.
 * <p>
 * Migration 6 has the same problem one step further in: its {@code hasColumn} guard skips the
 * {@code addColumn} on every database this suite ever builds, because the initial migration renders
 * {@code matches.givens} out of {@link Schema} as it stands now. Only a database that genuinely predates
 * the column takes the other branch, and building one is fiddlier than it looks - see
 * {@link #migrateToVersionFiveWithoutGivens()}.
 */
class SchemaMigrationTest extends PostgresTest {
	
	private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");
	private static final LocalDate DATE = LocalDate.of(2026, 8, 9);
	
	private final UserRepository users = new UserRepository();
	private final PreferenceRepository preferences = new PreferenceRepository();
	private final MatchRepository matches = new MatchRepository();
	private final DeviceRepository devices = new DeviceRepository();
	
	/**
	 * Every migration up to and including {@code version}.
	 * <p>
	 * By version rather than by position, so appending a migration cannot silently change which schema a
	 * test that wanted "the one before the rescale" actually boots.
	 */
	private static List<SqlMigration> upToVersion(int version) {
		return SchemaMigration.ALL.stream().filter(migration -> migration.version().getMajor() <= version).toList();
	}
	
	/**
	 * Rewinds to the schema as it stood before the rescale, so a row can be written in the old meaning.
	 * <p>
	 * The runner records what it has applied, so it is not enough to re-register a shorter list against
	 * the database {@link PostgresTest} already migrated - the schema has to go first.
	 */
	private void migrateToVersionBeforeRescale() {
		database.execute(transaction -> {
			transaction.dropSchema("public", true);
			transaction.createSchema("public");
		});
		this.runMigrations(upToVersion(6));
	}
	
	private void runMigrations(List<SqlMigration> migrations) {
		try {
			SqlMigrationRunner runner = SqlMigrationRunner.of(database.sql());
			runner.register(migrations);
			runner.migrate();
		} catch (SqlException e) {
			throw new IllegalStateException("Failed to apply the migrations under test", e);
		}
	}
	
	private UUID createUser(String displayName) {
		return database.transaction(transaction -> this.users.create(transaction, displayName, Role.MEMBER, NOW).id());
	}
	
	private UUID createDevice(UUID userId, String key) {
		return database.transaction(transaction -> this.devices.create(
			transaction, userId, key.getBytes(java.nio.charset.StandardCharsets.UTF_8), KeyAlgorithm.ED25519, key, NOW
		).id());
	}

	private int storedPreference(UUID userId) {
		return database.read(transaction -> this.preferences.dailyDifficulty(transaction, userId));
	}
	
	// --- the rescale ---
	
	@Test
	void rescale_aPreferenceWrittenOnTheOldScale_namesTheSameBandOnTheNewOne() {
		// The finding this migration exists for: a player who chose the fourth tier of six - the hard one -
		// kept the digit 4 and woke up on the fourth of fifteen, which is easy, with the setting still
		// reading 4 and looking untouched.
		this.migrateToVersionBeforeRescale();
		UUID player = this.createUser("Chose-The-Hard-One");
		database.execute(transaction -> this.preferences.setDailyDifficulty(transaction, player, 4, NOW));
		// Guards the arrangement itself: if the rewind failed and the rescale had already run, the row would
		// arrive here rewritten and every assertion below would pass without the migration doing anything.
		assertEquals(4, this.storedPreference(player), "the row must still be on the old scale before the rescale runs");
		
		Migrations.migrate(database);
		
		assertEquals(LegacyDifficulty.fromLegacy(4).index(), this.storedPreference(player));
		assertEquals(10, this.storedPreference(player));
	}
	
	@Test
	void rescale_everyLegacyTierAtOnce_landsOnItsAnchor_andNoRowIsMovedTwice() {
		// All six present together is the case that catches a wrong statement order: rewritten ascending,
		// the rows that started at legacy 2 become 4 and are then dragged on to 10 by the statement for
		// legacy 4, and two tiers collapse into one.
		this.migrateToVersionBeforeRescale();
		UUID[] players = new UUID[LegacyDifficulty.LEGACY_LISA + 1];
		for (int legacy = 1; legacy <= LegacyDifficulty.LEGACY_LISA; legacy++) {
			players[legacy] = this.createUser("Legacy-" + legacy);
			int tier = legacy;
			database.execute(transaction -> this.preferences.setDailyDifficulty(transaction, players[tier], tier, NOW));
		}
		
		Migrations.migrate(database);
		
		assertAll(
			() -> assertEquals(1, this.storedPreference(players[1])),
			() -> assertEquals(4, this.storedPreference(players[2])),
			() -> assertEquals(7, this.storedPreference(players[3])),
			() -> assertEquals(10, this.storedPreference(players[4])),
			() -> assertEquals(13, this.storedPreference(players[5])),
			() -> assertEquals(Difficulty.LISA.index(), this.storedPreference(players[LegacyDifficulty.LEGACY_LISA]))
		);
	}
	
	@Test
	void rescale_aDailyAssignmentLockedInBeforeTheDeploy_isReplayedAtTheSameBand() {
		// An assignment is what decides which grid a date's daily is. Left on the old scale it would verify
		// a solve against a puzzle the player never saw.
		this.migrateToVersionBeforeRescale();
		UUID player = this.createUser("Mid-Daily");
		database.execute(transaction -> this.preferences.assign(transaction, player, DATE, 5));
		
		Migrations.migrate(database);
		
		Integer assigned = database.read(transaction -> this.preferences.assignedDifficulty(transaction, player, DATE));
		assertEquals(13, assigned);
	}
	
	@Test
	void rescale_aFreshDatabase_appliesCleanlyOverEmptyTables() {
		// The ordinary path, and the one every deploy after the first takes: nothing to rewrite, and the
		// migration must still apply and record itself rather than fail on an empty table.
		assertEquals(SchemaMigration.CURRENT_VERSION, Migrations.migrate(database));
	}
	
	// --- the add-column guard ---
	
	/**
	 * Builds the one thing the suite otherwise never has: a database at version 5 whose {@code matches}
	 * table predates {@code givens}, recorded bookkeeping included.
	 * <p>
	 * Migrating to 5 is not enough on its own, and that is exactly why this branch was never covered.
	 * {@code SchemaMigration.table} renders the initial migration from {@link Schema} as it stands
	 * <em>now</em>, so a database created today already has the column by the time migration 6 runs,
	 * {@code hasColumn} answers true and the {@code addColumn} inside it never executes.
	 * <p>
	 * <strong>The order below is load-bearing, and dropping the column after migrating to 5 does not
	 * work.</strong> The {@code SqlMigrationSchema} that {@code hasColumn} is asked is not live
	 * introspection - it is the snapshot LUtils recorded when the <em>last</em> migration was applied. The
	 * runner does introspect the real database to build that snapshot, but only at the moment a migration
	 * commits, so an {@code ALTER TABLE} run afterwards is invisible to the guard and migration 6 still
	 * takes the skip branch. The drop therefore has to happen while there is still a migration left to
	 * apply: 1 to 4 first, then the column goes, then 5 commits and snapshots a schema that genuinely has
	 * no {@code givens} - which is the state a database deployed before that column existed is really in.
	 */
	private void migrateToVersionFiveWithoutGivens() {
		database.execute(transaction -> {
			transaction.dropSchema("public", true);
			transaction.createSchema("public");
		});
		this.runMigrations(upToVersion(4));
		database.withConnection(connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute("ALTER TABLE " + MATCHES.name() + " DROP COLUMN " + MATCH_GIVENS.name());
			}
			return null;
		});
		this.runMigrations(upToVersion(5));
	}
	
	private boolean matchesHasGivens() {
		return database.withConnection(connection -> {
			try (PreparedStatement statement = connection.prepareStatement(
				"SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?"
			)) {
				statement.setString(1, MATCHES.name());
				statement.setString(2, MATCH_GIVENS.name());
				try (ResultSet rows = statement.executeQuery()) {
					return rows.next();
				}
			}
		});
	}
	
	@Test
	void matchGivens_onADatabaseWhoseMatchesTablePredatesTheColumn_addsIt() {
		// The branch this covers has never run: every other test starts from a schema where the initial
		// migration already rendered givens out of Schema, so hasColumn is true and the addColumn inside it
		// is skipped. A production database deployed before the column existed takes the other path, and
		// until now nothing proved that path even compiles into working DDL.
		this.migrateToVersionFiveWithoutGivens();
		assertFalse(this.matchesHasGivens(), "the column must be absent before the migration runs, or this test proves nothing");
		
		this.runMigrations(upToVersion(6));
		
		assertTrue(this.matchesHasGivens());
	}
	
	@Test
	void matchGivens_afterTheColumnIsAddedBackOntoAnOlderTable_carriesAMatchsGivens() {
		// Adding the column is only half of it: the point of migration 6 is that a match created afterwards
		// stores its grid, so the column has to be one the ordinary write path can actually use.
		this.migrateToVersionFiveWithoutGivens();
		this.runMigrations(upToVersion(6));
		UUID creator = this.createUser("Given-Carrier");
		
		Match created = database.transaction(transaction -> this.matches.create(
			transaction, MatchMode.RACE, creator, GridSize.NINE, Variant.CLASSIC, Difficulty.THREE, 4242L, "ABCDEF", false, true, 0, "CODE1234", NOW
		));
		Match reloaded = database.read(transaction -> this.matches.find(transaction, created.id()));
		
		assertNotNull(reloaded);
		assertEquals("ABCDEF", reloaded.givens());
	}
	
	// --- per-device sessions ---

	/**
	 * Builds a database whose {@code sessions} table is genuinely the pre-v9 one: unique on
	 * {@code user_id}, no {@code superseded_*} columns.
	 * <p>
	 * The order is load-bearing for the reason spelled out on {@link #migrateToVersionFiveWithoutGivens()}
	 * - the guards inside migration 9 read the snapshot LUtils recorded when the <em>last</em> migration
	 * committed, not the live database. So the table is put back into its old shape while migration 8 is
	 * still pending, and 8's commit is what records a schema that really does look deployed.
	 */
	private void migrateToVersionEightWithPerUserSessions() {
		database.execute(transaction -> {
			transaction.dropSchema("public", true);
			transaction.createSchema("public");
		});
		this.runMigrations(upToVersion(7));
		database.withConnection(connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute("ALTER TABLE sessions DROP COLUMN superseded_token");
				statement.execute("ALTER TABLE sessions DROP COLUMN superseded_at");
				statement.execute("ALTER TABLE sessions DROP CONSTRAINT sessions_device_id_key");
				statement.execute("ALTER TABLE sessions ADD CONSTRAINT sessions_user_id_key UNIQUE (user_id)");
			}
			return null;
		});
		this.runMigrations(upToVersion(8));
	}

	private boolean sessionsColumnIsUnique(String column) {
		return database.withConnection(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("""
				SELECT 1
				FROM pg_index index_
				JOIN pg_class table_ ON table_.oid = index_.indrelid
				JOIN pg_attribute attribute ON attribute.attrelid = table_.oid AND attribute.attnum = ANY (index_.indkey)
				WHERE table_.relname = 'sessions' AND attribute.attname = ? AND index_.indisunique AND index_.indnatts = 1
				"""
			)) {
				statement.setString(1, column);
				try (ResultSet rows = statement.executeQuery()) {
					return rows.next();
				}
			}
		});
	}

	@Test
	void perDeviceSessions_onADeployedDatabase_movesTheUniquenessFromTheUserToTheDevice() {
		// Neither branch of migration 9 is exercised by an ordinary run: a database built today already has
		// the constraints the right way round, so both guards skip. Only a database that predates the
		// change takes the other path, and that path is the one every deployed server will actually run.
		this.migrateToVersionEightWithPerUserSessions();
		assertAll(
			() -> assertTrue(this.sessionsColumnIsUnique("user_id"), "the arrangement must start from the old shape, or this test proves nothing"),
			() -> assertFalse(this.sessionsColumnIsUnique("device_id"))
		);

		this.runMigrations(upToVersion(9));

		assertAll(
			() -> assertFalse(this.sessionsColumnIsUnique("user_id"), "a user may be signed in on several devices"),
			() -> assertTrue(this.sessionsColumnIsUnique("device_id"), "but a device holds exactly one session")
		);
	}

	@Test
	void perDeviceSessions_onADeployedDatabase_letsTwoDevicesOfOneUserHoldASessionEachAfterwards() {
		// The DDL landing is only half of it: the point is that the write path can then do what the old
		// constraint refused, which is what the two-devices bug was.
		this.migrateToVersionEightWithPerUserSessions();
		this.runMigrations(upToVersion(9));
		UUID player = this.createUser("Two-Devices");
		UUID phone = this.createDevice(player, "phone-key");
		UUID tablet = this.createDevice(player, "tablet-key");

		SessionRepository sessions = new SessionRepository();
		database.execute(transaction -> sessions.replace(transaction, Session.issued("token-phone", player, phone, NOW, NOW.plusSeconds(3600))));
		database.execute(transaction -> sessions.replace(transaction, Session.issued("token-tablet", player, tablet, NOW, NOW.plusSeconds(3600))));

		assertEquals(2, database.read(transaction -> sessions.findAllByUser(transaction, player)).size());
	}

	@Test
	void perDeviceSessions_onAFreshDatabase_appliesCleanlyAndSkipsBothConstraintChanges() {
		// The other branch: a database created after this version exists already has the constraints the
		// right way round, and re-running the drop or the add on it would fail rather than no-op.
		assertEquals(SchemaMigration.CURRENT_VERSION, Migrations.migrate(database));
		assertAll(
			() -> assertFalse(this.sessionsColumnIsUnique("user_id")),
			() -> assertTrue(this.sessionsColumnIsUnique("device_id"))
		);
	}

	// --- the list itself ---
	
	@Test
	void all_versionsAreContiguousFromOne_andEndAtCurrentVersion() {
		// The runner keys its bookkeeping on these, so a gap or a repeat silently changes which migration a
		// half-upgraded database believes it has applied.
		for (int index = 0; index < SchemaMigration.ALL.size(); index++) {
			assertEquals(index + 1, SchemaMigration.ALL.get(index).version().getMajor(), "migration at position " + index);
		}
		assertEquals(SchemaMigration.CURRENT_VERSION, SchemaMigration.ALL.size());
	}
}
