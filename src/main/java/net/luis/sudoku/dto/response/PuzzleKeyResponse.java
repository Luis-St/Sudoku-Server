package net.luis.sudoku.dto.response;

import net.luis.sudoku.compat.LegacyDifficulty;
import net.luis.sudoku.key.PuzzleKey;
import org.jspecify.annotations.NonNull;

/**
 * A puzzle key on the wire, <b>v1 only</b>.
 * <p>
 * The grid is never sent - only this. The client regenerates a byte-identical puzzle from it through
 * the same shared-core version (server-spec 1, 8).
 * <p>
 * <b>The difficulty here is the frozen six-tier integer</b>, not the real fifteen-tier index: a client
 * already in the wild reads {@code 6} as Lisa and cannot name anything above it. v2 clients get
 * {@link PuzzleResponse} instead, which carries the real index and the givens.
 *
 * @param genVersion generation version the key was stamped with
 * @param size grid edge length
 * @param variant {@code CLASSIC} or {@code CHAOS}
 * @param difficulty legacy tier index 1-6, where 6 is Lisa
 * @param seed the 64-bit seed, as a string because JSON numbers lose precision above 2^53
 */
public record PuzzleKeyResponse(int genVersion, int size, @NonNull String variant, int difficulty, @NonNull String seed) {
	
	/**
	 * @param key The key to report
	 * @return It with its tier reduced to the six-tier scale v1 speaks
	 */
	public static @NonNull PuzzleKeyResponse of(@NonNull PuzzleKey key) {
		return new PuzzleKeyResponse(
			key.genVersion(),
			key.size().n(),
			key.variant().name(),
			LegacyDifficulty.toLegacy(key.difficulty()),
			Long.toString(key.seed())
		);
	}
}
