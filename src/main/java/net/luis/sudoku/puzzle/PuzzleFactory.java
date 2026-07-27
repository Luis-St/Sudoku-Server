package net.luis.sudoku.puzzle;

import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.generation.PuzzleGenerator;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.key.PuzzleKey;
import org.jspecify.annotations.NonNull;

/**
 * The server's single entry point to shared-core generation.
 * <p>
 * Everything that needs a puzzle - daily issuance, result verification, match creation - goes through
 * here, so issuance and verification can never drift onto different code paths or different
 * {@code genVersion}s.
 */
public final class PuzzleFactory {
	
	private PuzzleFactory() {}
	
	/**
	 * Generates the puzzle for a key.
	 * <p>
	 * Deterministic by shared-core's guarantee: the client regenerates a byte-identical grid from the
	 * same key, which is why only the key is ever sent (feature-spec 3.2).
	 */
	public static @NonNull GeneratedPuzzle generate(@NonNull PuzzleKey key) {
		return PuzzleGenerator.generate(key);
	}
	
	/**
	 * Builds a key, rejecting configurations the server must never issue.
	 *
	 * @throws ApiException {@code LISA_NOT_ALLOWED} if Lisa is requested, or {@code BAD_REQUEST} if the
	 *   variant is unsupported at that size
	 */
	public static @NonNull PuzzleKey key(@NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty, long seed) {
		requireMultiplayerSafe(difficulty);
		if (!variant.isSupportedAt(size)) {
			throw ApiException.badRequest(variant + " is not supported at " + size.n() + "x" + size.n());
		}
		return PuzzleKey.of(size, variant, difficulty, seed);
	}
	
	/**
	 * Rejects Lisa, which carries single-player gameplay modifiers and never has a server-side meaning
	 * (server-spec 10.1, 16).
	 */
	public static void requireMultiplayerSafe(@NonNull Difficulty difficulty) {
		if (difficulty.isLisa()) {
			throw new ApiException(ErrorCode.LISA_NOT_ALLOWED, "Lisa is a single-player difficulty");
		}
	}
	
	/**
	 * Parses a 1-5 difficulty index from a request.
	 *
	 * @throws ApiException {@code LISA_NOT_ALLOWED} for index 6, {@code BAD_REQUEST} otherwise
	 */
	public static @NonNull Difficulty difficultyOfIndex(int index) {
		Difficulty difficulty;
		try {
			difficulty = Difficulty.ofIndex(index);
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("difficulty must be between 1 and 5, got: " + index);
		}
		requireMultiplayerSafe(difficulty);
		return difficulty;
	}
}
