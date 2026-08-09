package net.luis.sudoku.dto.response;

import net.luis.sudoku.compat.LegacyDifficulty;
import net.luis.sudoku.difficulty.Difficulty;
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
 * @param difficulty tier index 1-15
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
	
	/**
	 * @param entry The stored aggregate
	 * @return It with the real fifteen-tier index, for v2 callers
	 */
	public static @NonNull StatsEntryResponse of(@NonNull StatsEntry entry) {
		return of(entry, entry.difficulty());
	}
	
	/**
	 * The same row with its tier reduced to the six-tier scale a v1 client speaks.
	 * <p>
	 * Lossy in one direction, unavoidably: fifteen tiers do not fit in six values, so two real tiers can
	 * land on one legacy row in the list a v1 client renders. It is still the honest answer - the alternative
	 * is showing a tier number that client has no name for.
	 *
	 * @param entry The stored aggregate
	 * @return It with a 1-6 tier index
	 */
	public static @NonNull StatsEntryResponse legacy(@NonNull StatsEntry entry) {
		return of(entry, LegacyDifficulty.toLegacy(Difficulty.ofIndex(entry.difficulty())));
	}
	
	private static @NonNull StatsEntryResponse of(@NonNull StatsEntry entry, int difficulty) {
		return new StatsEntryResponse(
			entry.size(),
			entry.variant(),
			difficulty,
			entry.gamesPlayed(),
			entry.solved(),
			entry.failed(),
			entry.bestTimeMs(),
			entry.averageTimeMs(),
			entry.hintsUsed()
		);
	}
}
