package net.luis.sudoku.domain;

import net.luis.sudoku.error.ApiException;
import org.jspecify.annotations.NonNull;

/**
 * How a daily attempt ended (server-spec 8.2).
 */
public enum DailyOutcome {
	
	/** The grid was completed. Locks the date: no further attempts are accepted. */
	SOLVED,
	/** Lives were exhausted. May be retried for the rest of the day. */
	FAILED;
	
	public static @NonNull DailyOutcome of(@NonNull String name) {
		try {
			return valueOf(name.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("outcome must be SOLVED or FAILED, got: " + name);
		}
	}
}
