package net.luis.sudoku.match;

/**
 * The match lifecycle (server-spec 10.1): {@code CREATED -> WAITING -> RUNNING -> ENDED}, or
 * {@code ABANDONED}.
 */
public enum MatchState {
	
	/** Created by its creator; not yet open for joining. */
	CREATED,
	/** Open, waiting for participants to fill the capacity and declare themselves ready. */
	WAITING,
	/** Under way. Stakes are escrowed at the transition into this state. */
	RUNNING,
	/** Finished with a decided result. */
	ENDED,
	/** Ended without a result: disconnect beyond the grace window, explicit quit, or a restart. */
	ABANDONED;
	
	public boolean isTerminal() {
		return this == ENDED || this == ABANDONED;
	}
}
