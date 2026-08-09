package net.luis.sudoku.db.schema;

import net.luis.sudoku.compat.LegacyDifficulty;
import net.luis.sudoku.db.Migrations;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.repository.PreferenceRepository;
import net.luis.sudoku.repository.UserRepository;
import net.luis.sudoku.support.PostgresTest;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.migration.SqlMigration;
import net.luis.utils.io.database.migration.SqlMigrationRunner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link SchemaMigration}, and specifically for the one migration that rewrites data
 * rather than shape.
 * <p>
 * The rest of the suite proves nothing about the rescale: {@link PostgresTest} starts every test from a
 * database migrated all the way to {@link SchemaMigration#CURRENT_VERSION}, so the rescale runs against
 * empty tables and passes whether it works or not. These tests deliberately stop at the version
 * <em>before</em> it, write rows that mean what they meant then, and only then let it run - which is the
 * only arrangement in which it can be wrong.
 */
class SchemaMigrationTest extends PostgresTest {
	
	private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");
	private static final LocalDate DATE = LocalDate.of(2026, 8, 9);
	
	private final UserRepository users = new UserRepository();
	private final PreferenceRepository preferences = new PreferenceRepository();
	
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
		this.runMigrations(SchemaMigration.ALL.subList(0, SchemaMigration.ALL.size() - 1));
	}
	
	private void runMigrations(List<SqlMigration> migrations) {
		try {
			SqlMigrationRunner runner = SqlMigrationRunner.of(database.sql());
			runner.register(migrations);
			runner.migrate();
		} catch (SqlException e) {
			throw new IllegalStateException("Failed to migrate to the version before the rescale", e);
		}
	}
	
	private UUID createUser(String displayName) {
		return database.transaction(transaction -> this.users.create(transaction, displayName, Role.MEMBER, NOW).id());
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
