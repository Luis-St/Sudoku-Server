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
 * @param restorePoints banked streak-restore points, capped at {@link #MAX_RESTORE_POINTS}
 */
public record Streak(@NonNull UUID userId, int current, int longest, @Nullable LocalDate lastCompletedDate, int restorePoints) {
	
	/** Every this many consecutive days banks one restore point. */
	public static final int POINT_THRESHOLD_DAYS = 7;
	/** A banked point repairs one missed day at {@code RESTORE_COST_PER_DAY} Rhubarb. */
	public static final int MAX_RESTORE_POINTS = 3;
	
	public static @NonNull Streak none(@NonNull UUID userId) {
		return new Streak(userId, 0, 0, null, 0);
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
	 * <p>
	 * A broken streak resets {@code current} to 1 but leaves {@code restorePoints} untouched: banked
	 * points are a spendable resource, not streak progress, and restoring a gap is exactly what they are
	 * for.
	 */
	public @NonNull Streak completedOn(@NonNull LocalDate date) {
		if (this.lastCompletedDate == null) {
			return new Streak(this.userId, 1, Math.max(1, this.longest), date, this.restorePoints);
		}
		if (!date.isAfter(this.lastCompletedDate)) {
			return this;
		}
		
		boolean consecutive = this.lastCompletedDate.plusDays(1).equals(date);
		int next = consecutive ? this.current + 1 : 1;
		int points = consecutive && next % POINT_THRESHOLD_DAYS == 0
			? Math.min(MAX_RESTORE_POINTS, this.restorePoints + 1)
			: this.restorePoints;
		return new Streak(this.userId, next, Math.max(next, this.longest), date, points);
	}
	
	/**
	 * Spends {@code days} restore points to repair a gap through {@code through} (typically yesterday),
	 * so a normal submission today sees a consecutive continuation.
	 * <p>
	 * Pure: the caller validates the spend is affordable (points and currency) before calling this.
	 */
	public @NonNull Streak restoredBy(int days, @NonNull LocalDate through) {
		int next = this.current + days;
		return new Streak(this.userId, next, Math.max(next, this.longest), through, this.restorePoints - days);
	}
}
