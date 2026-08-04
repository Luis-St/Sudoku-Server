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
 * @param online whether their presence heartbeat is still fresh - "reachable for a match request right
 *   now", not "authenticated recently", which is what {@code lastSeenAt} already says
 * @param revoked whether this player has been kicked. Only an admin ever receives such a row at all, and
 *   it is there so they can offer to reinstate them (spec 7.2); for everyone else this is always false.
 */
public record PlayerResponse(@NonNull String id, @NonNull String displayName, @NonNull String role, int streak,
                             @Nullable String lastSeenAt, boolean online, boolean revoked) {

	public static @NonNull PlayerResponse of(@NonNull PlayerSummary summary, boolean online) {
		return new PlayerResponse(summary.id().toString(), summary.displayName(), summary.role(), summary.streak(), summary.lastSeenAt(), online,
			summary.revoked());
	}
}
