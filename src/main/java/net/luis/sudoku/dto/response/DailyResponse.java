package net.luis.sudoku.dto.response;

import net.luis.sudoku.daily.DailyService.Daily;
import net.luis.sudoku.daily.SeedDerivation;
import org.jspecify.annotations.NonNull;

/**
 * Response to {@code GET /api/v1/daily}.
 *
 * @param date {@code yyyy-MM-dd} in the server's rollover zone
 * @param puzzleKey everything needed to regenerate the grid locally
 */
public record DailyResponse(@NonNull String date, @NonNull PuzzleKeyResponse puzzleKey) {
	
	public static @NonNull DailyResponse of(@NonNull Daily daily) {
		return new DailyResponse(SeedDerivation.format(daily.date()), PuzzleKeyResponse.of(daily.key()));
	}
}
