package net.luis.sudoku.dto.response;

import net.luis.sudoku.daily.SeedDerivation;
import net.luis.sudoku.generation.GeneratedPuzzle;
import org.jspecify.annotations.NonNull;

import java.time.LocalDate;

/**
 * Response to {@code GET /api/v2/daily}.
 * <p>
 * The v1 shape's {@code puzzleKey} is replaced by {@code puzzle}, which carries the real fifteen-tier
 * difficulty and the givens; see {@link PuzzleResponse}. {@link DailyResponse} keeps serving the v1 path
 * with the six-tier integer and no givens.
 *
 * @param date {@code yyyy-MM-dd} in the server's rollover zone
 * @param puzzle the grid itself, plus the key it was generated from
 */
public record DailyV2Response(@NonNull String date, @NonNull PuzzleResponse puzzle) {
	
	/**
	 * @param date The daily's date in the server zone
	 * @param generated The puzzle issued for that date and tier
	 * @return The response body
	 */
	public static @NonNull DailyV2Response of(@NonNull LocalDate date, @NonNull GeneratedPuzzle generated) {
		return new DailyV2Response(SeedDerivation.format(date), PuzzleResponse.of(generated));
	}
}
