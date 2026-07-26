package net.luis.sudoku.dto.response;

import net.luis.sudoku.domain.StatsEntry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One aggregate row as seen by clients (server-spec 9).
 * <p>
 * The mean is computed here rather than stored, because the running total is what can be merged
 * incrementally.
 *
 * @param size grid edge length
 * @param variant {@code CLASSIC} or {@code CHAOS}
 * @param difficulty tier index 1-6
 * @param gamesPlayed games finished
 * @param solved successes
 * @param failed failures
 * @param bestTimeMs fastest solve, or null if never solved
 * @param averageTimeMs mean solve time, or null if never solved
 * @param hintsUsed hints consumed
 */
public record StatsEntryResponse(
	int size,
	@NonNull String variant,
	int difficulty,
	int gamesPlayed,
	int solved,
	int failed,
	@Nullable Long bestTimeMs,
	@Nullable Long averageTimeMs,
	int hintsUsed
) {
	
	public static @NonNull StatsEntryResponse of(@NonNull StatsEntry entry) {
		return new StatsEntryResponse(
			entry.size(),
			entry.variant(),
			entry.difficulty(),
			entry.gamesPlayed(),
			entry.solved(),
			entry.failed(),
			entry.bestTimeMs(),
			entry.averageTimeMs(),
			entry.hintsUsed()
		);
	}
}
