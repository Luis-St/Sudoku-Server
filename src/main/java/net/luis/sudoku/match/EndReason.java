package net.luis.sudoku.match;

/**
 * Why a match ended, sent to clients in {@code MATCH_ENDED} (server-spec 10.3).
 */
public enum EndReason {
	
	/** A participant completed the grid. */
	COMPLETED,
	/** Lives were exhausted. */
	LIVES_EXHAUSTED,
	/** A participant resigned explicitly. */
	RESIGNED,
	/** A duel participant backgrounded the app; the opponent takes the pot (spec 11.2). */
	FORFEIT_BACKGROUNDED,
	/** A participant failed to return within the reconnect grace window. */
	DISCONNECTED,
	/** The reconnect cap was exceeded. */
	RECONNECT_LIMIT,
	/** The duel handover cap was reached; decided on correct cells (spec 11.2). */
	STALEMATE,
	/** The server restarted mid-match; stakes are refunded (spec 9a.3). */
	SERVER_RESTART,
	/** The creator called the match off before anybody joined it; nothing was ever escrowed. */
	CANCELLED
}
