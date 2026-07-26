package net.luis.sudoku.match;

/**
 * How one participant's match ended.
 */
public enum MatchResult {
	
	WON,
	LOST,
	/** Co-op success, or a race where both participants exhausted their lives. */
	DRAW,
	/** Left, or was dropped past the reconnect grace window. */
	ABANDONED
}
