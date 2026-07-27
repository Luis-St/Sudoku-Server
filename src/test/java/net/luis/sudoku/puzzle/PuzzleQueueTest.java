package net.luis.sudoku.puzzle;

import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link PuzzleQueue}.
 */
class PuzzleQueueTest {
	
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
	void take_withLisa_isRejected() {
		// The queue only serves normal and match play, and Lisa is single-player only.
		try (PuzzleQueue queue = new PuzzleQueue(() -> 0)) {
			ApiException e = assertThrows(ApiException.class,
				() -> queue.take(GridSize.NINE, Variant.CLASSIC, Difficulty.LISA));
			assertEquals(ErrorCode.LISA_NOT_ALLOWED, e.code());
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
