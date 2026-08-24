package net.luis.sudoku.dto.response;

import net.luis.sudoku.daily.DailyService.Submission;
import net.luis.sudoku.domain.Streak;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

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
	
	public static @NonNull DailyResultResponse of(@NonNull Submission submission, @NonNull LocalDate today) {
		return new DailyResultResponse(
			submission.accepted(),
			submission.verified(),
			submission.result().attemptNo(),
			StreakResponse.of(submission.streak(), today)
		);
	}
	
	/**
	 * @param current consecutive days ending at {@code lastCompletedDate}
	 * @param longest best run ever achieved
	 * @param lastCompletedDate ISO-8601 date of the most recent day solved, or null if never
	 * @param restorePoints banked streak-restore points, each worth one repaired missed day
	 * @param restorableMissedDays how many missed days a restore would repair right now, 0 if none - the
	 *   still-open gap, or the break already solved past while its window is open
	 * @param restorableUntil ISO-8601 last day the remembered break can be restored, or null when there is
	 *   no remembered break; the client shows it so the closing window is never a surprise (issue 2.2.0/6)
	 */
	public record StreakResponse(
		int current, int longest, @Nullable String lastCompletedDate, int restorePoints,
		int restorableMissedDays, @Nullable String restorableUntil
	) {
		
		public static @NonNull StreakResponse of(@NonNull Streak streak, @NonNull LocalDate today) {
			LocalDate restorableUntil = streak.restorableUntil();
			return new StreakResponse(streak.current(), streak.longest(),
				streak.lastCompletedDate() == null ? null : streak.lastCompletedDate().toString(), streak.restorePoints(),
				streak.repairableMissedDays(today), restorableUntil == null ? null : restorableUntil.toString());
		}
	}
}
