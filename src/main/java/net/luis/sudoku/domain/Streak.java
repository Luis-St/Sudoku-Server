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
	 * Merges a streak a client counted locally into this one, taking whichever knows about more days.
	 * <p>
	 * <strong>This is the one place the server takes a player's word for a streak.</strong> Everywhere
	 * else the count is earned through {@link #completedOn} from a replay-verified solve, and that is
	 * still the only way to <em>start</em> one that the leaderboard respects. This exists because the two
	 * numbers can legitimately diverge with no fault on the player's side: a daily solved while the server
	 * was unreachable advances the device's count, and if the queued submission is later lost - dropped as
	 * unsalvageable, or discarded by an app upgrade - the server never hears about that day and no
	 * mechanism existed to tell it afterwards.
	 * <p>
	 * Strictly one-way: a claim lower than what is already stored is ignored, so this can raise a count
	 * but never lower one, and repeating the same call changes nothing. That makes it safe to send on
	 * every reconnect without the client tracking what it has already published.
	 * <p>
	 * {@code restorePoints} are deliberately untouched. They are a spendable resource earned every
	 * {@link #POINT_THRESHOLD_DAYS} days through verified solves, and minting them from an unverified
	 * claim would turn a self-reported number into currency.
	 *
	 * @param claimedCurrent the client's own consecutive-day count
	 * @param claimedLastCompleted the day that count ends on, which becomes the anchor the next verified
	 *   solve continues from
	 * @return the merged streak, or {@code this} when the claim adds nothing
	 */
	public @NonNull Streak mergedWith(int claimedCurrent, @NonNull LocalDate claimedLastCompleted) {
		if (claimedCurrent <= this.current) {
			return this;
		}
		// Keep the later anchor: a bigger count ending earlier than what is stored would move the streak
		// backwards in time, and the next verified solve would then read as a broken run rather than a
		// continuation.
		LocalDate anchor = this.lastCompletedDate == null || claimedLastCompleted.isAfter(this.lastCompletedDate)
			? claimedLastCompleted
			: this.lastCompletedDate;
		return new Streak(this.userId, claimedCurrent, Math.max(claimedCurrent, this.longest), anchor, this.restorePoints);
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
