package net.luis.sudoku.dto.response;

import net.luis.sudoku.repository.DailyLeaderboardRepository.Entry;
import org.jspecify.annotations.NonNull;

/**
 * One row of the daily leaderboard (server-spec 8.6).
 * <p>
 * Hints used are deliberately absent: they are recorded, but exposing them would turn a speed ranking
 * into a purity contest.
 *
 * @param userId the player
 * @param displayName their name
 * @param elapsedMs their time on the successful attempt
 * @param attempts which attempt succeeded
 */
public record LeaderboardEntryResponse(@NonNull String userId, @NonNull String displayName, long elapsedMs, int attempts) {
	
	public static @NonNull LeaderboardEntryResponse of(@NonNull Entry entry) {
		return new LeaderboardEntryResponse(entry.userId().toString(), entry.displayName(), entry.elapsedMs(),
			entry.attempts());
	}
}
