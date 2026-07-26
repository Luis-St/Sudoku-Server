package net.luis.sudoku.currency;

/**
 * Why a currency ledger row exists (server-spec 9a).
 * <p>
 * The ledger is append-only and the balance is derived as {@code SUM(delta)}, so every change to a
 * player's balance is explained by exactly one of these.
 */
public enum LedgerReason {
	
	/** A normal game solved: {@code 5 x difficultyIndex}, capped per day. */
	EARN_GAME,
	/** A daily solved: {@code 5 x difficultyIndex + 20}, once per date and outside the daily cap. */
	EARN_DAILY,
	/** Escrowed at match start, in the same transaction that moves the match to RUNNING. */
	STAKE,
	/** The whole pot credited to a match winner. */
	PAYOUT,
	/** Returned on abandonment, disconnect beyond the grace window, or crash recovery. */
	REFUND,
	/** A silent correction applied when a client's reported balance fails the plausibility check. */
	SYNC_ADJUST
}
