package net.luis.sudoku.domain;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A player's daily-solve streak (server-spec 8.3).
 *
 * @param userId the player
 * @param current consecutive days ending at {@code lastCompletedDate}
 * @param longest the best run ever achieved
 * @param lastCompletedDate the most recent day solved, or null if never
 */
public record Streak(@NonNull UUID userId, int current, int longest, @Nullable LocalDate lastCompletedDate) {
	
	public static @NonNull Streak none(@NonNull UUID userId) {
		return new Streak(userId, 0, 0, null);
	}
	
	/**
	 * Credits a solve on {@code date}.
	 * <p>
	 * Idempotent by design (spec 8.3): re-crediting the same date returns {@code this}, so a retried
	 * submission cannot inflate the count. Continuity is evaluated in whole days, and a gap of more than
	 * one day restarts at 1 rather than resuming.
	 * <p>
	 * A submission for a date <em>earlier</em> than the last completed one - which the offline queue can
	 * produce - does not extend the streak, because doing so would need the whole history rather than
	 * just the last date.
	 */
	public @NonNull Streak completedOn(@NonNull LocalDate date) {
		if (this.lastCompletedDate == null) {
			return new Streak(this.userId, 1, Math.max(1, this.longest), date);
		}
		if (!date.isAfter(this.lastCompletedDate)) {
			return this;
		}
		
		boolean consecutive = this.lastCompletedDate.plusDays(1).equals(date);
		int next = consecutive ? this.current + 1 : 1;
		return new Streak(this.userId, next, Math.max(next, this.longest), date);
	}
}
