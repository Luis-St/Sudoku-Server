package net.luis.sudoku.dto.response;

import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.key.PuzzleKey;
import net.luis.sudoku.puzzle.PuzzleFactory;
import org.jspecify.annotations.NonNull;

/**
 * A puzzle on the wire, {@code v2}: the key <em>and</em> the finished givens.
 * <p>
 * This is what {@link PuzzleKeyResponse} could not be. A key alone made every client regenerate the grid
 * itself, which under the fifteen-band rater costs up to a second on a desktop JVM and several times that
 * on a phone - paid on every game start and every saved-game restore, on every device, for a puzzle the
 * server had already generated once. The givens are 42 bytes at 9x9, so the server pays that cost once and
 * the client decodes instead, falling back to generating from the key only when it is offline.
 * <p>
 * The key still travels alongside and is still authoritative: it carries the {@code genVersion} a client
 * refuses a mismatch on, and for a chaos grid it is what the region layout is derived from, since the
 * givens carry nothing but digits.
 *
 * @param genVersion generation version the key was stamped with
 * @param size grid edge length
 * @param variant {@code CLASSIC} or {@code CHAOS}
 * @param difficulty the real tier index 1-15, where 15 is Lisa - <b>not</b> the v1 six-tier integer
 * @param seed the 64-bit seed, as a string because JSON numbers lose precision above 2^53
 * @param givens the givens, bit-packed and rendered in URL-safe Base64 without padding
 */
public record PuzzleResponse(int genVersion, int size, @NonNull String variant, int difficulty, @NonNull String seed, @NonNull String givens) {
	
	/**
	 * @param generated The puzzle the server generated
	 * @return It as a v2 payload
	 */
	public static @NonNull PuzzleResponse of(@NonNull GeneratedPuzzle generated) {
		PuzzleKey key = generated.key();
		return new PuzzleResponse(
			key.genVersion(),
			key.size().n(),
			key.variant().name(),
			// The band the grid rated, not the band the request asked for. The generator returns its closest
			// candidate when the search misses, so those two differ often enough to matter - and this number is
			// what the player is shown and what CurrencyService pays against, so a request here is a lie that
			// costs money.
			generated.rated().index(),
			Long.toString(key.seed()),
			PuzzleFactory.encodeGivens(generated)
		);
	}
}
