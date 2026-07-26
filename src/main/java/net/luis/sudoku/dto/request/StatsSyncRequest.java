package net.luis.sudoku.dto.request;

import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.stats.StatsService.SyncEntry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Body of {@code POST /api/v1/stats/sync} (server-spec 9).
 *
 * @param entries the client's local aggregates, one per (size, variant, difficulty)
 */
public record StatsSyncRequest(@Nullable List<LocalStats> entries) {
	
	public @NonNull List<SyncEntry> parseEntries() {
		if (this.entries == null || this.entries.isEmpty()) {
			return List.of();
		}
		
		List<SyncEntry> parsed = new ArrayList<>(this.entries.size());
		for (LocalStats entry : this.entries) {
			if (entry == null) {
				throw ApiException.badRequest("stats sync contains a null entry");
			}
			parsed.add(entry.toSyncEntry());
		}
		return parsed;
	}
	
	/**
	 * One uploaded aggregate.
	 *
	 * @param size grid edge length
	 * @param variant {@code CLASSIC} or {@code CHAOS}
	 * @param difficulty tier index 1-6; Lisa is a genuine single-player tier and is accepted here
	 * @param gamesPlayed games finished
	 * @param solved successes
	 * @param failed failures
	 * @param bestTimeMs fastest solve, or null if never solved
	 * @param totalTimeMs summed solve time
	 * @param hintsUsed hints consumed
	 */
	public record LocalStats(
		@Nullable Integer size,
		@Nullable String variant,
		@Nullable Integer difficulty,
		@Nullable Integer gamesPlayed,
		@Nullable Integer solved,
		@Nullable Integer failed,
		@Nullable Long bestTimeMs,
		@Nullable Long totalTimeMs,
		@Nullable Integer hintsUsed
	) {
		
		private static int require(@Nullable Integer value, @NonNull String field) {
			if (value == null) {
				throw ApiException.badRequest("Missing required field in stats sync entry: " + field);
			}
			return value;
		}
		
		private static int orZero(@Nullable Integer value) {
			return value == null ? 0 : value;
		}
		
		@NonNull SyncEntry toSyncEntry() {
			return new SyncEntry(
				require(this.size, "size"),
				Requests.require(this.variant, "variant").trim().toUpperCase(),
				require(this.difficulty, "difficulty"),
				orZero(this.gamesPlayed),
				orZero(this.solved),
				orZero(this.failed),
				this.bestTimeMs,
				this.totalTimeMs == null ? 0 : this.totalTimeMs,
				orZero(this.hintsUsed)
			);
		}
	}
}
