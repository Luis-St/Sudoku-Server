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
 * @param breakMissedDays the days missed by the most recent break that has not been repaired, 0 if there
 *   is none
 * @param breakPreviousStreak the run length that break ended, so repairing it can hand the days back
 * @param breakRecordedOn the day the run restarted after that break, from which its restore window runs;
 *   null if there is no unrepaired break
 */
public record Streak(
	@NonNull UUID userId, int current, int longest, @Nullable LocalDate lastCompletedDate, int restorePoints,
	int breakMissedDays, int breakPreviousStreak, @Nullable LocalDate breakRecordedOn
) {
	
	/** Every this many consecutive days banks one restore point. */
	public static final int POINT_THRESHOLD_DAYS = 7;
	/** A banked point repairs one missed day at {@code RESTORE_COST_PER_DAY} Rhubarb. */
	public static final int MAX_RESTORE_POINTS = 3;
	/**
	 * How many days after the run restarts a break stays repairable (issue 2.2.0/6).
	 * <p>
	 * A gap used to be visible only as the distance between {@code lastCompletedDate} and today, which meant
	 * solving today's daily erased it: the last completed date became today, the distance became zero, and
	 * every restore afterwards was refused as {@code STREAK_RESTORE_NOT_NEEDED} with the banked points still
	 * sitting there unspendable. Since playing the daily is the one thing a player in that position is
	 * certain to do, the window was in practice closed before they ever saw it. The break is therefore
	 * remembered on the row and stays repairable for this many days past the restart.
	 */
	public static final int RESTORE_WINDOW_DAYS = 7;
	
	public static @NonNull Streak none(@NonNull UUID userId) {
		return new Streak(userId, 0, 0, null, 0, 0, 0, null);
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
			return new Streak(this.userId, 1, Math.max(1, this.longest), date, this.restorePoints, this.breakMissedDays, this.breakPreviousStreak, this.breakRecordedOn);
		}
		if (!date.isAfter(this.lastCompletedDate)) {
			return this;
		}
		
		boolean consecutive = this.lastCompletedDate.plusDays(1).equals(date);
		int next = consecutive ? this.current + 1 : 1;
		int points = consecutive && next % POINT_THRESHOLD_DAYS == 0
			? Math.min(MAX_RESTORE_POINTS, this.restorePoints + 1)
			: this.restorePoints;
		if (consecutive) {
			return new Streak(this.userId, next, Math.max(next, this.longest), date, points, this.breakMissedDays, this.breakPreviousStreak, this.breakRecordedOn);
		}
		
		// The break this solve just walked past, written down so it survives its own repair window rather
		// than being erased by the very solve that restarted the run - see RESTORE_WINDOW_DAYS. Only the
		// most recent one is kept: an older unrepaired break is superseded here, since a player who has let
		// a second run lapse is no longer restoring the first.
		int missed = (int) (date.toEpochDay() - this.lastCompletedDate.toEpochDay()) - 1;
		return new Streak(this.userId, next, Math.max(next, this.longest), date, points, missed, this.current, date);
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
		return new Streak(this.userId, claimedCurrent, Math.max(claimedCurrent, this.longest), anchor, this.restorePoints, this.breakMissedDays, this.breakPreviousStreak, this.breakRecordedOn);
	}
	
	/**
	 * Spends {@code days} restore points to repair a gap through {@code through} (typically yesterday),
	 * so a normal submission today sees a consecutive continuation.
	 * <p>
	 * Pure: the caller validates the spend is affordable (points and currency) before calling this.
	 */
	public @NonNull Streak restoredBy(int days, @NonNull LocalDate through) {
		int next = this.current + days;
		return new Streak(this.userId, next, Math.max(next, this.longest), through, this.restorePoints - days, this.breakMissedDays, this.breakPreviousStreak, this.breakRecordedOn);
	}
	
	/**
	 * The last day the remembered break can still be repaired, or null when there is none to repair.
	 *
	 * @return {@link #breakRecordedOn} plus {@link #RESTORE_WINDOW_DAYS}, the day the offer expires after
	 */
	public @Nullable LocalDate restorableUntil() {
		if (this.breakRecordedOn == null || this.breakMissedDays <= 0) {
			return null;
		}
		return this.breakRecordedOn.plusDays(RESTORE_WINDOW_DAYS);
	}
	
	/** Whether the remembered break is still inside its window on {@code today}. */
	public boolean hasRepairableBreak(@NonNull LocalDate today) {
		LocalDate until = this.restorableUntil();
		return until != null && !today.isAfter(until);
	}
	
	/**
	 * The days a restore would repair right now: the live gap while today is still unsolved, and otherwise
	 * the remembered break for as long as its window is open. 0 when there is nothing to repair.
	 */
	public int repairableMissedDays(@NonNull LocalDate today) {
		if (this.lastCompletedDate != null) {
			int gap = (int) (today.toEpochDay() - this.lastCompletedDate.toEpochDay()) - 1;
			if (gap > 0) {
				return gap;
			}
		}
		return this.hasRepairableBreak(today) ? this.breakMissedDays : 0;
	}
	
	/**
	 * Repairs the remembered break: the days it cost and the run it ended are handed back, so the count
	 * reads as though the gap had been bridged before the current run started.
	 * <p>
	 * The anchor is deliberately left alone - unlike {@link #restoredBy}, which patches a still-open gap up
	 * to yesterday, this repairs a break the player has already solved past, and moving
	 * {@code lastCompletedDate} backwards would make the next solve read as a fresh break.
	 * <p>
	 * Pure: the caller validates the spend is affordable (points and currency) before calling this.
	 */
	public @NonNull Streak repairedBreak() {
		int next = this.current + this.breakMissedDays + this.breakPreviousStreak;
		return new Streak(this.userId, next, Math.max(next, this.longest), this.lastCompletedDate, this.restorePoints - this.breakMissedDays, 0, 0, null);
	}
}
