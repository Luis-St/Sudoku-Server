package net.luis.sudoku.puzzle;

import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.support.MovableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link PuzzleQueue}.
 */
class PuzzleQueueTest {
	
	/** Waits for the single asynchronous thing this class does, rather than sleeping a fixed amount. */
	private static int awaitDepth(PuzzleQueue queue, GridSize size, Variant variant, Difficulty difficulty) throws Exception {
		int depth = 0;
		for (int attempt = 0; attempt < 100 && depth == 0; attempt++) {
			Thread.sleep(50);
			depth = queue.depth(size, variant, difficulty);
		}
		return depth;
	}
	
	@Test
	void take_fromAColdQueue_stillReturnsAPuzzle() {
		// A miss falls back to inline generation: a cold queue is slower, never wrong.
		try (PuzzleQueue queue = new PuzzleQueue(() -> 0)) {
			GeneratedPuzzle puzzle = queue.take(GridSize.NINE, Variant.CLASSIC, Difficulty.THREE);
			
			assertAll(
				() -> assertEquals(GridSize.NINE, puzzle.puzzle().size()),
				() -> assertEquals(Variant.CLASSIC, puzzle.puzzle().variant()),
				() -> assertEquals(Difficulty.THREE, puzzle.key().difficulty())
			);
		}
	}
	
	@Test
	void refill_aBucketNobodyHasTakenFromInAnHour_isDropped() throws Exception {
		// The bucket space is the product of three axes and grew two and a half times when the difficulty
		// scale did. Without ageing, every combination a single curious player ever opened stayed pooled at
		// full depth for the life of the process, and the pool's memory was decided by what had been asked
		// for once rather than by what is in use.
		MovableClock clock = new MovableClock();
		try (PuzzleQueue queue = new PuzzleQueue(() -> 2, clock)) {
			queue.take(GridSize.FOUR, Variant.CLASSIC, Difficulty.ONE);
			assertTrue(awaitDepth(queue, GridSize.FOUR, Variant.CLASSIC, Difficulty.ONE) > 0, "the bucket should have filled");
			
			clock.advance(Duration.ofHours(2));
			// Any refill sweeps; this take is on a different bucket precisely so the drop cannot be
			// mistaken for the taken-from bucket simply being drained.
			queue.take(GridSize.NINE, Variant.CLASSIC, Difficulty.ONE);
			awaitDepth(queue, GridSize.NINE, Variant.CLASSIC, Difficulty.ONE);
			
			assertEquals(0, queue.depth(GridSize.FOUR, Variant.CLASSIC, Difficulty.ONE));
		}
	}
	
	@Test
	void refill_aBucketStillInUse_isKept() throws Exception {
		// The other half, and the one that matters more: taking from a bucket stamps it, so the busiest
		// bucket on the server is never the one the sweep decides is idle.
		MovableClock clock = new MovableClock();
		try (PuzzleQueue queue = new PuzzleQueue(() -> 2, clock)) {
			queue.take(GridSize.FOUR, Variant.CLASSIC, Difficulty.ONE);
			assertTrue(awaitDepth(queue, GridSize.FOUR, Variant.CLASSIC, Difficulty.ONE) > 0, "the bucket should have filled");
			
			clock.advance(Duration.ofHours(2));
			queue.take(GridSize.FOUR, Variant.CLASSIC, Difficulty.ONE); // Still wanted, so still stamped.
			awaitDepth(queue, GridSize.FOUR, Variant.CLASSIC, Difficulty.ONE);
			
			assertTrue(queue.depth(GridSize.FOUR, Variant.CLASSIC, Difficulty.ONE) > 0, "a bucket in use must survive the sweep");
		}
	}
	
	@Test
	void take_repeatedly_yieldsDistinctPuzzles() {
		// Seeds come from SecureRandom, so pooled puzzles must not repeat.
		try (PuzzleQueue queue = new PuzzleQueue(() -> 0)) {
			Set<Long> seeds = new HashSet<>();
			for (int i = 0; i < 8; i++) {
				seeds.add(queue.take(GridSize.NINE, Variant.CLASSIC, Difficulty.TWO).key().seed());
			}
			assertEquals(8, seeds.size());
		}
	}
	
	@Test
	void take_afterTheWorkerHasRun_servesFromThePool() throws Exception {
		try (PuzzleQueue queue = new PuzzleQueue(() -> 2)) {
			queue.take(GridSize.NINE, Variant.CLASSIC, Difficulty.ONE);
			
			// The refill is asynchronous; poll rather than sleeping a fixed amount.
			int depth = 0;
			for (int attempt = 0; attempt < 100 && depth == 0; attempt++) {
				Thread.sleep(50);
				depth = queue.depth(GridSize.NINE, Variant.CLASSIC, Difficulty.ONE);
			}
			
			assertTrue(depth > 0, "the worker should have topped the bucket up");
		}
	}
	
	@Test
	void targetDepth_scalesWithTheActivePlayerCount_withinBounds() {
		AtomicInteger players = new AtomicInteger(0);
		try (PuzzleQueue queue = new PuzzleQueue(players::get)) {
			assertEquals(4, queue.targetDepth(), "a floor applies with nobody connected");
			
			players.set(6);
			assertEquals(12, queue.targetDepth(), "twice the active-player count");
			
			players.set(1000);
			assertEquals(32, queue.targetDepth(), "capped so memory stays bounded");
		}
	}
	
	@Test
	void take_withLisa_isServed() {
		// The queue pools every band now that it also serves POST /api/v2/puzzles: Lisa is the most expensive
		// tier to generate and therefore the one that most needs pooling. Refusing it is a rule about
		// matches, and it still runs in MatchService.create.
		try (PuzzleQueue queue = new PuzzleQueue(() -> 0)) {
			GeneratedPuzzle puzzle = queue.take(GridSize.NINE, Variant.CLASSIC, Difficulty.LISA);
			assertEquals(Difficulty.LISA, puzzle.key().difficulty());
		}
	}
	
	@Test
	void take_poolsTheWholePuzzle_notJustItsKey() {
		// The givens have to be computed by the time a request arrives, which is only true if the pool holds
		// the generated puzzle rather than the key it came from.
		try (PuzzleQueue queue = new PuzzleQueue(() -> 0)) {
			GeneratedPuzzle puzzle = queue.take(GridSize.NINE, Variant.CLASSIC, Difficulty.THREE);
			
			assertAll(
				() -> assertEquals(GridSize.NINE.cellCount(), puzzle.solution().length),
				() -> assertNotNull(PuzzleFactory.encodeGivens(puzzle))
			);
		}
	}
	
	@Test
	void take_aChaosPuzzle_isServedToo() {
		try (PuzzleQueue queue = new PuzzleQueue(() -> 0)) {
			GeneratedPuzzle puzzle = queue.take(GridSize.NINE, Variant.CHAOS, Difficulty.THREE);
			assertEquals(Variant.CHAOS, puzzle.puzzle().variant());
		}
	}
}
