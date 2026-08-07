package net.luis.sudoku.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /api/v1/preferences}.
 *
 * @param dailyDifficulty tier index 1-6; the daily is single-player, so Lisa is accepted here
 */
public record PreferencesRequest(@Nullable Integer dailyDifficulty) {
	
	public int requireDailyDifficulty() {
		return Requests.requirePositive(this.dailyDifficulty, "dailyDifficulty");
	}
}
