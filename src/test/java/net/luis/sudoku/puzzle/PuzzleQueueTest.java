package net.luis.sudoku.puzzle;

import net.luis.sudoku.config.PoolConfig;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.difficulty.DifficultyBands;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.repository.PuzzlePoolRepository;
import net.luis.sudoku.version.GenVersion;
import net.luis.sudoku.support.MovableClock;
import net.luis.sudoku.support.PostgresTest;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link PuzzleQueue}.
 * <p>
 * Every test here runs against a real database, because there is no other configuration any more. The queue
 * used to accept a null one and keep its pool in a map, and this file was the tests of that map; the pool is
 * now the table and nothing else, so "a queue with no database" is not a thing to test but a thing that
 * cannot be built. {@code PuzzleQueuePersistenceTest} covers what the table specifically buys - surviving a
 * restart and being shared with a second instance.
 */
class PuzzleQueueTest extends PostgresTest {

	private static final GridSize SIZE = GridSize.NINE;
	private static final Variant VARIANT = Variant.CLASSIC;

	/** A floor of one everywhere, so a refill is a single generation and the tests stay quick. */
	private static PoolConfig shallowPool() {
		return poolWith(1);
	}

	private static PoolConfig poolWith(int floor) {
		Map<GridSize, Integer> depths = new EnumMap<>(GridSize.class);
		for (GridSize size : GridSize.values()) {
			depths.put(size, floor);
		}
		return new PoolConfig(depths, 32, false);
	}

	private PuzzleQueue queue(IntSupplier players) {
		return new PuzzleQueue(players, new MovableClock(), database, shallowPool());
	}

	/** Waits for the asynchronous refill rather than sleeping a fixed amount. */
	private int awaitDepth(PuzzleQueue queue, GridSize size, Variant variant, Difficulty difficulty) throws Exception {
		int depth = 0;
		for (int attempt = 0; attempt < 100 && depth == 0; attempt++) {
			Thread.sleep(50);
			depth = queue.depth(size, variant, difficulty);
		}
		return depth;
	}

	@Test
	void take_fromAColdQueue_stillReturnsAPuzzle() {
		// A miss falls back to inline generation: a cold pool is slower, never wrong.
		try (PuzzleQueue queue = this.queue(() -> 0)) {
			GeneratedPuzzle puzzle = queue.take(SIZE, VARIANT, Difficulty.THREE);

			assertAll(
				() -> assertEquals(SIZE, puzzle.puzzle().size()),
				() -> assertEquals(VARIANT, puzzle.puzzle().variant()),
				() -> assertEquals(Difficulty.THREE, puzzle.key().difficulty())
			);
		}
	}

	@Test
	void take_repeatedly_yieldsDistinctPuzzles() {
		// Seeds come from SecureRandom, so pooled puzzles must not repeat.
		try (PuzzleQueue queue = this.queue(() -> 0)) {
			Set<Long> seeds = new HashSet<>();
			for (int i = 0; i < 8; i++) {
				seeds.add(queue.take(SIZE, VARIANT, Difficulty.TWO).key().seed());
			}
			assertEquals(8, seeds.size());
		}
	}

	@Test
	void take_refillsTheBucketItDrainedFrom() throws Exception {
		// Refill on every take, hit or miss: a hit has just made the bucket one shallower than its floor, and
		// a miss means it was already under it. This is what keeps a floor a floor rather than a starting depth.
		try (PuzzleQueue queue = this.queue(() -> 0)) {
			queue.take(SIZE, VARIANT, Difficulty.ONE);

			assertTrue(this.awaitDepth(queue, SIZE, VARIANT, Difficulty.ONE) > 0, "the take should have triggered a refill");
		}
	}

	@Test
	void depth_isReadFromTheTable_notFromMemory() throws Exception {
		// The buffer is gone, so a depth this reports is a depth a second instance can also see. Before, up to
		// two puzzles per bucket lived only in this process and no SELECT could count them.
		try (PuzzleQueue queue = this.queue(() -> 0)) {
			queue.take(SIZE, VARIANT, Difficulty.ONE);
			int reported = this.awaitDepth(queue, SIZE, VARIANT, Difficulty.ONE);

			int inTable = database.read(transaction ->
				new PuzzlePoolRepository().count(transaction, SIZE, VARIANT, Difficulty.ONE, GenVersion.CURRENT));
			assertEquals(inTable, reported);
		}
	}

	@Test
	void targetDepth_scalesWithTheActivePlayerCount_withinBounds() {
		AtomicInteger players = new AtomicInteger(0);
		try (PuzzleQueue queue = new PuzzleQueue(players::get, new MovableClock(), database, poolWith(4))) {
			assertEquals(4, queue.targetDepth(SIZE), "the configured floor applies with nobody connected");

			players.set(6);
			assertEquals(12, queue.targetDepth(SIZE), "twice the active-player count");

			players.set(1000);
			assertEquals(32, queue.targetDepth(SIZE), "capped so the pool stays bounded");
		}
	}

	@Test
	void targetDepth_isPerSize_soOneNumberDoesNotHaveToServeBoth() {
		// 16x16 costs two orders of magnitude more to generate than 9x9, which is the whole reason the floor
		// is configured per size rather than shared.
		Map<GridSize, Integer> depths = new EnumMap<>(GridSize.class);
		for (GridSize size : GridSize.values()) {
			depths.put(size, 10);
		}
		depths.put(GridSize.SIXTEEN, 2);

		try (PuzzleQueue queue = new PuzzleQueue(() -> 0, new MovableClock(), database, new PoolConfig(depths, 32, false))) {
			assertAll(
				() -> assertEquals(10, queue.targetDepth(GridSize.NINE)),
				() -> assertEquals(2, queue.targetDepth(GridSize.SIXTEEN))
			);
		}
	}

	@Test
	void targetDepth_aFloorOfZero_optsTheSizeOutOfPoolingEntirely() {
		Map<GridSize, Integer> depths = new EnumMap<>(GridSize.class);
		for (GridSize size : GridSize.values()) {
			depths.put(size, 0);
		}

		try (PuzzleQueue queue = new PuzzleQueue(() -> 500, new MovableClock(), database, new PoolConfig(depths, 32, false))) {
			// Even with five hundred players connected: zero means zero, not "scale from zero".
			assertEquals(0, queue.targetDepth(SIZE));
		}
	}

	@Test
	void warm_queuesEveryBucketTheLibrarySupports() {
		// The guarantee is for all supported (size, variant, difficulty) combinations, so the count has to come
		// from the library rather than from a number written down here that could drift away from it.
		int expected = 0;
		for (GridSize size : GridSize.values()) {
			for (Variant variant : Variant.values()) {
				if (variant.isSupportedAt(size)) {
					expected += DifficultyBands.defaults().supported(size, variant).size();
				}
			}
		}

		try (PuzzleQueue queue = this.queue(() -> 0)) {
			assertEquals(expected, queue.warm());
		}
	}

	@Test
	void warm_skipsSizesWhoseFloorIsZero() {
		Map<GridSize, Integer> depths = new EnumMap<>(GridSize.class);
		for (GridSize size : GridSize.values()) {
			depths.put(size, size == GridSize.SIXTEEN ? 0 : 1);
		}
		int sixteen = DifficultyBands.defaults().supported(GridSize.SIXTEEN, Variant.CLASSIC).size()
			+ DifficultyBands.defaults().supported(GridSize.SIXTEEN, Variant.CHAOS).size();

		try (PuzzleQueue all = this.queue(() -> 0); PuzzleQueue without = new PuzzleQueue(() -> 0, new MovableClock(), database, new PoolConfig(depths, 32, false))) {
			assertEquals(all.warm() - sixteen, without.warm());
		}
	}

	@Test
	void warm_doesNotQueueTheChaosBandsSixteenCannotReach() {
		// The variant split, seen from the pool: 16x16 chaos supports eight bands, not fifteen, so warming must
		// not create seven buckets whose rows would be filed under a band the grid never rates at.
		assertEquals(8, DifficultyBands.defaults().supported(GridSize.SIXTEEN, Variant.CHAOS).size());
		assertEquals(15, DifficultyBands.defaults().supported(GridSize.SIXTEEN, Variant.CLASSIC).size());
	}

	@Test
	void take_withLisa_isServed() {
		// The queue pools every band now that it also serves POST /api/v2/puzzles: Lisa is the most expensive
		// tier to generate and therefore the one that most needs pooling. Refusing it is a rule about
		// matches, and it still runs in MatchService.create.
		try (PuzzleQueue queue = this.queue(() -> 0)) {
			GeneratedPuzzle puzzle = queue.take(SIZE, VARIANT, Difficulty.LISA);
			assertEquals(Difficulty.LISA, puzzle.key().difficulty());
		}
	}

	@Test
	void take_poolsTheWholePuzzle_notJustItsKey() {
		// The givens have to be computed by the time a request arrives, which is only true if the pool holds
		// the generated puzzle rather than the key it came from.
		try (PuzzleQueue queue = this.queue(() -> 0)) {
			GeneratedPuzzle puzzle = queue.take(SIZE, VARIANT, Difficulty.THREE);

			assertAll(
				() -> assertEquals(SIZE.cellCount(), puzzle.solution().length),
				() -> assertNotNull(PuzzleFactory.encodeGivens(puzzle))
			);
		}
	}

	@Test
	void take_aChaosPuzzle_isServedToo() {
		try (PuzzleQueue queue = this.queue(() -> 0)) {
			GeneratedPuzzle puzzle = queue.take(SIZE, Variant.CHAOS, Difficulty.THREE);
			assertEquals(Variant.CHAOS, puzzle.puzzle().variant());
		}
	}

	@Test
	void generationTimeout_isLongerForSixteen_becauseItsTailIs() {
		// A flat thirty seconds was documented as far above the worst measured generation. That was true of
		// every size except the one that matters, so the guard is now scaled rather than shared.
		assertAll(
			() -> assertEquals(30, PuzzleQueue.generationTimeout(GridSize.NINE).toSeconds()),
			() -> assertEquals(30, PuzzleQueue.generationTimeout(GridSize.TWELVE).toSeconds()),
			() -> assertTrue(PuzzleQueue.generationTimeout(GridSize.SIXTEEN).toSeconds() > 30)
		);
	}

	@Test
	void constructor_withoutADatabase_isNotPossible() {
		assertThrows(NullPointerException.class, () -> new PuzzleQueue(() -> 0, new MovableClock(), null, shallowPool()));
	}
}
