package net.luis.sudoku.dto.response;

import net.luis.sudoku.daily.DailyService.Submission;
import net.luis.sudoku.domain.Streak;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Response to {@code POST /api/v1/daily/result}.
 *
 * @param accepted whether the result was stored
 * @param verified whether the solve-order replay passed; an unverified result still counts for the
 *   player but is excluded from anything shown to others (server-spec 8.2)
 * @param attemptNo which attempt this was
 * @param streak the streak after this submission
 */
public record DailyResultResponse(boolean accepted, boolean verified, int attemptNo, @NonNull StreakResponse streak) {
	
	public static @NonNull DailyResultResponse of(@NonNull Submission submission) {
		return new DailyResultResponse(
			submission.accepted(),
			submission.verified(),
			submission.result().attemptNo(),
			StreakResponse.of(submission.streak())
		);
	}
	
	/**
	 * @param current consecutive days ending at {@code lastCompletedDate}
	 * @param longest best run ever achieved
	 * @param lastCompletedDate ISO-8601 date of the most recent day solved, or null if never
	 * @param restorePoints banked streak-restore points, each worth one repaired missed day
	 */
	public record StreakResponse(int current, int longest, @Nullable String lastCompletedDate, int restorePoints) {
		
		public static @NonNull StreakResponse of(@NonNull Streak streak) {
			return new StreakResponse(streak.current(), streak.longest(),
				streak.lastCompletedDate() == null ? null : streak.lastCompletedDate().toString(), streak.restorePoints());
		}
	}
}
