package net.luis.sudoku.puzzle;

import net.luis.sudoku.db.schema.Schema.PuzzlePoolRow;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.key.PuzzleKey;
import net.luis.sudoku.repository.PuzzlePoolRepository;
import net.luis.sudoku.support.MovableClock;
import net.luis.sudoku.support.PostgresTest;
import net.luis.sudoku.version.GenVersion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static net.luis.sudoku.db.schema.Schema.PUZZLE_POOL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for the half of {@link PuzzleQueue} that {@code PuzzleQueueTest} cannot reach: the pool that
 * outlives the process.
 * <p>
 * Everything in {@code PuzzleQueueTest} runs against a queue built without a database, which is the queue
 * as it was before {@code puzzle_pool} existed and is still exactly what a database this cannot reach
 * degrades to. These tests are the other configuration, and they are about the two things a map in one
 * process could not do: survive a restart, and be shared with a second instance.
 */
class PuzzleQueuePersistenceTest extends PostgresTest {
	
	private static final GridSize SIZE = GridSize.NINE;
	private static final Variant VARIANT = Variant.CLASSIC;
	private static final Difficulty BAND = Difficulty.ONE;
	
	private final PuzzlePoolRepository pool = new PuzzlePoolRepository();
	
	/** Waits for the background refill rather than sleeping a fixed amount, as the in-memory tests do. */
	private int awaitStored() throws Exception {
		int stored = 0;
		for (int attempt = 0; attempt < 100 && stored == 0; attempt++) {
			Thread.sleep(50);
			stored = this.stored();
		}
		return stored;
	}
	
	private int stored() {
		return database.read(transaction -> this.pool.count(transaction, SIZE, VARIANT, BAND, GenVersion.CURRENT));
	}
	
	/** Puts one ready puzzle in the pool, standing in for whatever an earlier process left there. */
	private void storePregenerated() {
		this.storeRow(GenVersion.CURRENT, System.nanoTime());
	}
	
	private void storeRow(int genVersion, long seed) {
		GeneratedPuzzle generated = PuzzleFactory.generate(PuzzleKey.of(SIZE, VARIANT, BAND, seed));
		PuzzleKey key = generated.key();
		database.execute(transaction -> this.pool.store(transaction, List.of(
			new PuzzlePoolRow(0L, genVersion, key.size(), key.variant(), key.difficulty(), key.seed(), PuzzleFactory.encodeGivens(generated), new MovableClock().instant())
		)));
	}
	
	private Set<Long> storedSeeds() {
		List<PuzzlePoolRow> rows = database.read(transaction -> transaction.from(PUZZLE_POOL).select().fetch());
		return rows.stream().map(PuzzlePoolRow::seed).collect(Collectors.toSet());
	}
	
	@Test
	void refill_withADatabase_putsThePoolInTheTableRatherThanInMemory() throws Exception {
		// The point of the whole change: a restart used to throw away everything the process had paid to
		// generate, because the depth lived in a map. If the depth is still in memory afterwards, nothing
		// about that has actually changed.
		try (PuzzleQueue queue = new PuzzleQueue(() -> 0, new MovableClock(), database)) {
			queue.take(SIZE, VARIANT, BAND);
			
			assertTrue(this.awaitStored() > 0, "the worker should have filled the table");
			assertTrue(queue.depth(SIZE, VARIANT, BAND) <= 2, "the in-memory part is a buffer, not the pool");
		}
	}
	
	@Test
	void take_onAQueueThatDidNotFillThePool_servesWhatTheEarlierProcessLeftBehind() {
		// The restart, and the second instance, are the same case from here: a queue with nothing in memory
		// over a table somebody else filled. The row is written directly rather than by a first queue on
		// purpose - a queue's worker keeps generating in the background, and a test that has to guess when
		// it stopped proves less than one that knows exactly which puzzles were there.
		this.storePregenerated();
		Set<Long> generatedBefore = this.storedSeeds();
		assertEquals(1, generatedBefore.size(), "the pool must hold exactly the puzzle this test put there");
		
		try (PuzzleQueue queue = new PuzzleQueue(() -> 0, new MovableClock(), database)) {
			GeneratedPuzzle served = queue.take(SIZE, VARIANT, BAND);
			
			assertAll(
				() -> assertTrue(generatedBefore.contains(served.key().seed()), "a pooled puzzle should have been served rather than a fresh one"),
				// Not a depth check: the worker is already generating replacements behind this, and the
				// question is only whether this puzzle can still be handed to a second player.
				() -> assertFalse(this.storedSeeds().contains(served.key().seed()), "a served puzzle must be gone from the pool")
			);
		}
	}
	
	@Test
	void take_withAnEmptyTable_stillReturnsAPuzzle() {
		// The fallback that made the queue safe to add in the first place, and it has to keep working now
		// that a miss also means "the table had nothing", not only "the map had nothing".
		try (PuzzleQueue queue = new PuzzleQueue(() -> 0, new MovableClock(), database)) {
			GeneratedPuzzle puzzle = queue.take(SIZE, VARIANT, BAND);
			
			assertAll(
				() -> assertEquals(SIZE, puzzle.puzzle().size()),
				() -> assertEquals(BAND, puzzle.key().difficulty())
			);
		}
	}
	
	@Test
	void constructor_withPooledPuzzlesFromAnotherGenVersion_dropsThem() {
		// A pooled puzzle from a previous generator version names a grid this build regenerates differently.
		// Serving one is the mismatch GEN_VERSION_MISMATCH exists to prevent, so the rows go at startup.
		this.storeRow(GenVersion.CURRENT + 1, 42L);
		assertEquals(1, this.rows().size(), "the stale row must be there before the queue is built, or this proves nothing");
		
		try (PuzzleQueue queue = new PuzzleQueue(() -> 0, new MovableClock(), database)) {
			// Nothing is taken, so nothing refills: whatever is left is what the constructor decided to keep.
			assertEquals(0, queue.depth(SIZE, VARIANT, BAND));
			assertTrue(this.rows().isEmpty(), "a stale generator version must not be left in the pool");
		}
	}
	
	private List<PuzzlePoolRow> rows() {
		return database.read(transaction -> transaction.from(PUZZLE_POOL).select().fetch());
	}
}
