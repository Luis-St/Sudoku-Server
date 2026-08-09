package net.luis.sudoku.dto.response;

import net.luis.sudoku.generation.GeneratedPuzzle;
import org.jspecify.annotations.NonNull;

/**
 * Response to {@code POST /api/v2/puzzles}.
 * <p>
 * A one-field wrapper rather than the puzzle at the top level, so the body has room to grow - a share
 * code, a queue hint - without that being a contract change for anybody reading {@code puzzle}.
 *
 * @param puzzle the generated puzzle, givens included
 */
public record NewPuzzleResponse(@NonNull PuzzleResponse puzzle) {
	
	/**
	 * @param generated The puzzle the server generated
	 * @return The response body
	 */
	public static @NonNull NewPuzzleResponse of(@NonNull GeneratedPuzzle generated) {
		return new NewPuzzleResponse(PuzzleResponse.of(generated));
	}
}
