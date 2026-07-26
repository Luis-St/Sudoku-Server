package net.luis.sudoku.dto.response;

import net.luis.sudoku.key.PuzzleKey;
import org.jspecify.annotations.NonNull;

/**
 * A puzzle key on the wire.
 * <p>
 * The grid is never sent - only this. The client regenerates a byte-identical puzzle from it through
 * the same shared-core version (server-spec 1, 8).
 *
 * @param genVersion generation version the key was stamped with
 * @param size grid edge length
 * @param variant {@code CLASSIC} or {@code CHAOS}
 * @param difficulty tier index 1-5
 * @param seed the 64-bit seed, as a string because JSON numbers lose precision above 2^53
 */
public record PuzzleKeyResponse(int genVersion, int size, @NonNull String variant, int difficulty, @NonNull String seed) {
	
	public static @NonNull PuzzleKeyResponse of(@NonNull PuzzleKey key) {
		return new PuzzleKeyResponse(
			key.genVersion(),
			key.size().n(),
			key.variant().name(),
			key.difficulty().index(),
			Long.toString(key.seed())
		);
	}
}
