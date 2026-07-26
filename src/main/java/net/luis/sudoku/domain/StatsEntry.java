package net.luis.sudoku.domain;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * One aggregate row: a player's record at one {@code (size, variant, difficulty)} combination
 * (server-spec 9).
 *
 * @param userId the player
 * @param size grid edge length
 * @param variant {@code CLASSIC} or {@code CHAOS}
 * @param difficulty tier index 1-6, where 6 is Lisa (single-player only, but it does appear here)
 * @param gamesPlayed games started and finished
 * @param solved successful games
 * @param failed failed games
 * @param bestTimeMs fastest solve, or null if never solved
 * @param totalTimeMs summed solve time, from which the average is derived
 * @param hintsUsed hints consumed across all games
 */
public record StatsEntry(
	@NonNull UUID userId,
	int size,
	@NonNull String variant,
	int difficulty,
	int gamesPlayed,
	int solved,
	int failed,
	@Nullable Long bestTimeMs,
	long totalTimeMs,
	int hintsUsed
) {
	
	/**
	 * @return the mean solve time, or null when nothing has been solved yet
	 */
	public @Nullable Long averageTimeMs() {
		return this.solved == 0 ? null : this.totalTimeMs / this.solved;
	}
}
