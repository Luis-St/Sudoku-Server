package net.luis.sudoku.match;

import net.luis.sudoku.error.ApiException;
import org.jspecify.annotations.NonNull;

/**
 * The three multiplayer modes (server-spec 10, 11).
 */
public enum MatchMode {
	
	/** Same puzzle, independent boards, first to complete wins. Capacity 2. */
	RACE(2),
	/** Shared board and a server-owned time bank per player. Capacity 2. */
	DUEL(2),
	/** Shared pen layer, private pencil layers, optional shared lives pool. Up to 4. */
	COOP(4);
	
	private final int capacity;
	
	MatchMode(int capacity) {
		this.capacity = capacity;
	}
	
	public static @NonNull MatchMode of(@NonNull String name) {
		try {
			return valueOf(name.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("mode must be RACE, DUEL or COOP, got: " + name);
		}
	}
	
	/**
	 * @return how many participants this mode admits (spec 10.1)
	 */
	public int capacity() {
		return this.capacity;
	}
}
