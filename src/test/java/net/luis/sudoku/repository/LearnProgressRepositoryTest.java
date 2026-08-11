package net.luis.sudoku.repository;

import net.luis.sudoku.db.schema.Schema.LearnProgressRow;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.domain.User;
import net.luis.sudoku.learning.LearnService;
import net.luis.sudoku.support.PostgresTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link LearnProgressRepository}.
 * <p>
 * The merge rule is what these are about. Both devices of an account may work offline for as long as they
 * like, so an upload is not news just because it is the latest thing to arrive: a stale {@code PARTIAL}
 * landing on a {@code SOLVED} would take an achievement back off a player who has already been told they
 * earned it, and nothing anywhere would record that it had happened.
 */
class LearnProgressRepositoryTest extends PostgresTest {

	private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");
	private static final String TECHNIQUE = "NAKED_SINGLE";

	private final UserRepository users = new UserRepository();
	private final LearnProgressRepository progress = new LearnProgressRepository();

	/** The rows reference a user, so one has to exist for the foreign key to accept them. */
	private UUID user() {
		return database.transaction(transaction -> {
			User user = this.users.create(transaction, "player-" + UUID.randomUUID(), Role.NEW, NOW);
			return user.id();
		});
	}

	private static LearnProgressRow row(UUID userId, int level, int subLevel, String state) {
		return new LearnProgressRow(userId, TECHNIQUE, level, subLevel, state, NOW);
	}

	private List<LearnProgressRow> stored(UUID userId) {
		return database.transaction(transaction -> this.progress.forUser(transaction, userId));
	}

	@Test
	void mergeStoresWhatIsNotThereYet() {
		UUID userId = this.user();

		int accepted = database.transaction(transaction ->
			this.progress.merge(transaction, userId, List.of(row(userId, 1, 0, LearnProgressRepository.SOLVED))));

		assertEquals(1, accepted);
		assertEquals(1, this.stored(userId).size());
		assertEquals(LearnProgressRepository.SOLVED, this.stored(userId).getFirst().state());
	}

	@Test
	void mergeUpgradesAPartialToASolve() {
		UUID userId = this.user();
		database.transaction(transaction -> this.progress.merge(transaction, userId, List.of(row(userId, 1, 0, LearnProgressRepository.PARTIAL))));

		int accepted = database.transaction(transaction ->
			this.progress.merge(transaction, userId, List.of(row(userId, 1, 0, LearnProgressRepository.SOLVED))));

		assertEquals(1, accepted);
		assertEquals(LearnProgressRepository.SOLVED, this.stored(userId).getFirst().state());
	}

	@Test
	void mergeNeverDowngradesASolve() {
		UUID userId = this.user();
		database.transaction(transaction -> this.progress.merge(transaction, userId, List.of(row(userId, 1, 0, LearnProgressRepository.SOLVED))));

		// The second device has been offline since before the solve and reports what it last saw.
		int accepted = database.transaction(transaction ->
			this.progress.merge(transaction, userId, List.of(row(userId, 1, 0, LearnProgressRepository.PARTIAL))));

		assertEquals(0, accepted);
		assertEquals(LearnProgressRepository.SOLVED, this.stored(userId).getFirst().state());
	}

	@Test
	void mergeIsIdempotent() {
		UUID userId = this.user();
		List<LearnProgressRow> rows = List.of(row(userId, 1, 0, LearnProgressRepository.SOLVED));
		database.transaction(transaction -> this.progress.merge(transaction, userId, rows));

		int accepted = database.transaction(transaction -> this.progress.merge(transaction, userId, rows));

		assertEquals(0, accepted);
		assertEquals(1, this.stored(userId).size());
	}

	@Test
	void mergeAcceptsNothingForAnEmptyReport() {
		UUID userId = this.user();

		int accepted = database.transaction(transaction -> this.progress.merge(transaction, userId, List.of()));

		assertEquals(0, accepted);
		assertTrue(this.stored(userId).isEmpty());
	}

	@Test
	void oneUsersProgressIsNotAnothers() {
		UUID first = this.user();
		UUID second = this.user();
		database.transaction(transaction -> this.progress.merge(transaction, first, List.of(row(first, 1, 0, LearnProgressRepository.SOLVED))));

		assertEquals(1, this.stored(first).size());
		assertTrue(this.stored(second).isEmpty());
	}

	@Test
	void resetClearsOneTechniqueOnly() {
		UUID userId = this.user();
		database.transaction(transaction -> this.progress.merge(transaction, userId, List.of(
			row(userId, 1, 0, LearnProgressRepository.SOLVED),
			new LearnProgressRow(userId, "HIDDEN_PAIR", 1, 0, LearnProgressRepository.SOLVED, NOW)
		)));

		int removed = database.transaction(transaction -> this.progress.reset(transaction, userId, TECHNIQUE));

		assertEquals(1, removed);
		assertEquals(List.of("HIDDEN_PAIR"), this.stored(userId).stream().map(LearnProgressRow::technique).toList());
	}

	@Test
	void masteredCountsATechniqueOnlyOnceEveryExerciseIsSolved() {
		UUID userId = this.user();
		List<LearnProgressRow> rows = new java.util.ArrayList<>();
		for (int level = 1; level <= 3; level++) {
			for (int subLevel = 0; subLevel < 3; subLevel++) {
				rows.add(row(userId, level, subLevel, LearnProgressRepository.SOLVED));
			}
		}
		database.transaction(transaction -> this.progress.merge(transaction, userId, rows));

		assertEquals(1, LearnService.masteredCount(this.stored(userId)));
	}

	@Test
	void onePartialWithholdsMastery() {
		UUID userId = this.user();
		List<LearnProgressRow> rows = new java.util.ArrayList<>();
		for (int level = 1; level <= 3; level++) {
			for (int subLevel = 0; subLevel < 3; subLevel++) {
				boolean last = level == 3 && subLevel == 2;
				rows.add(row(userId, level, subLevel, last ? LearnProgressRepository.PARTIAL : LearnProgressRepository.SOLVED));
			}
		}
		database.transaction(transaction -> this.progress.merge(transaction, userId, rows));

		// Every exercise is finished and the technique is still unearned, which is exactly the state the
		// client has to draw differently or a stalled achievement looks like a bug.
		assertEquals(9, this.stored(userId).size());
		assertEquals(0, LearnService.masteredCount(this.stored(userId)));
	}
}
