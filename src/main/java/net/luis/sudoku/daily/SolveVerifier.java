package net.luis.sudoku.daily;

import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Puzzle;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Replays a submitted solve order against the regenerated puzzle (server-spec 8.2).
 * <p>
 * Deliberately basic, and the spec says so: this proves the submitted solution is correct and complete.
 * It does <em>not</em> prove the player reasoned it out - that is an accepted limitation (spec 12), not
 * a gap to close later.
 * <p>
 * A failure is not an error: the result is still stored, with {@code verified = false}, and simply
 * excluded from anything shown to other players.
 */
public final class SolveVerifier {
	
	/**
	 * Floor for how fast a grid can plausibly be entered, per cell the player had to fill.
	 * <p>
	 * Generous on purpose - this is meant to reject "a full 9x9 in two seconds", not to police fast
	 * players. At 150ms a 9x9 with 50 holes needs 7.5 seconds.
	 */
	public static final long MIN_MS_PER_CELL = 150;
	
	private SolveVerifier() {}
	
	/**
	 * @param puzzle the regenerated puzzle, including its known solution
	 * @param solveOrder the ordered entries the player committed
	 * @param elapsedMs wall time claimed
	 * @return the verification outcome, with a reason when it fails
	 */
	public static @NonNull Verification verify(@NonNull GeneratedPuzzle puzzle, @NonNull List<Entry> solveOrder,
	                                           long elapsedMs) {
		Puzzle grid = puzzle.puzzle();
		GridSize size = grid.size();
		int cellCount = size.cellCount();
		
		boolean[] filled = new boolean[cellCount];
		int holes = 0;
		for (int index = 0; index < cellCount; index++) {
			if (grid.cell(index).isGiven()) {
				filled[index] = true;
			} else {
				holes++;
			}
		}
		
		for (Entry entry : solveOrder) {
			int index = entry.cell();
			if (index < 0 || index >= cellCount) {
				return Verification.failed("Cell index out of range: " + index);
			}
			if (!size.isValidDigit(entry.digit())) {
				return Verification.failed("Digit out of range for this grid: " + entry.digit());
			}
			if (grid.cell(index).isGiven()) {
				return Verification.failed("Entry for a given cell: " + index);
			}
			if (filled[index]) {
				return Verification.failed("Duplicate entry for cell " + index);
			}
			if (puzzle.solutionAt(index) != entry.digit()) {
				return Verification.failed("Entry at cell " + index + " does not match the solution");
			}
			filled[index] = true;
		}
		
		for (int index = 0; index < cellCount; index++) {
			if (!filled[index]) {
				return Verification.failed("Grid is incomplete: cell " + index + " was never filled");
			}
		}
		
		long floor = holes * MIN_MS_PER_CELL;
		if (elapsedMs < floor) {
			return Verification.failed("Elapsed time " + elapsedMs + "ms is below the plausible floor of " + floor + "ms");
		}
		
		return Verification.passed();
	}
	
	/**
	 * One committed entry.
	 *
	 * @param cell cell index within the grid
	 * @param digit the digit placed
	 */
	public record Entry(int cell, int digit) {}
	
	/**
	 * @param verified whether the replay succeeded
	 * @param reason why it did not, or an empty string when it did
	 */
	public record Verification(boolean verified, @NonNull String reason) {
		
		static @NonNull Verification passed() {
			return new Verification(true, "");
		}
		
		static @NonNull Verification failed(@NonNull String reason) {
			return new Verification(false, reason);
		}
	}
}
