package net.luis.sudoku.dto.response;

import net.luis.sudoku.stats.StatsService.PlayerSummary;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A player in the browser (server-spec 9).
 *
 * @param id player id
 * @param displayName their name
 * @param role their role
 * @param streak current daily streak
 * @param lastSeenAt ISO-8601 last authentication, or null if never
 */
public record PlayerResponse(@NonNull String id, @NonNull String displayName, @NonNull String role, int streak,
                             @Nullable String lastSeenAt) {
	
	public static @NonNull PlayerResponse of(@NonNull PlayerSummary summary) {
		return new PlayerResponse(summary.id().toString(), summary.displayName(), summary.role(), summary.streak(), summary.lastSeenAt());
	}
}
