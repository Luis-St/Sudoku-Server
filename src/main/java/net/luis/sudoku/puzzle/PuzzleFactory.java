package net.luis.sudoku.puzzle;

import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.generation.PuzzleGenerator;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.key.PuzzleKey;
import net.luis.sudoku.sharecode.GivensCodec;
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
	 * Rebuilds the puzzle a set of encoded givens describes, instead of generating it again.
	 * <p>
	 * The givens are what the server already shipped to the clients, so rebuilding from them is both
	 * cheaper - a backtracking solve rather than a full generate-dig-rate search, which at the hard bands
	 * is the difference between milliseconds and a second - and safer, because it cannot disagree with the
	 * grid the players are looking at.
	 *
	 * @param key The key the givens belong to, which supplies the size, the variant and any chaos layout
	 * @param encodedGivens The {@code GivensCodec} string the givens were stored as
	 * @return The rebuilt puzzle and its derived solution
	 * @throws ApiException {@code INTERNAL} if the stored string is not givens for this key
	 */
	public static @NonNull GeneratedPuzzle fromGivens(@NonNull PuzzleKey key, @NonNull String encodedGivens) {
		try {
			return PuzzleGenerator.fromGivens(key, GivensCodec.decode(encodedGivens));
		} catch (IllegalArgumentException e) {
			// Only ever reachable if a row was written by something other than this server, so it is a bug
			// rather than a bad request; the caller falls back to regenerating from the key.
			throw new ApiException(ErrorCode.INTERNAL, "Stored givens do not match the puzzle key");
		}
	}
	
	/**
	 * Encodes a generated puzzle's givens for the wire and for storage.
	 *
	 * @param puzzle The puzzle to encode
	 * @return The compact, URL-safe Base64 string
	 */
	public static @NonNull String encodeGivens(@NonNull GeneratedPuzzle puzzle) {
		return GivensCodec.encode(puzzle.puzzle());
	}
	
	/**
	 * Builds a key for content two players share, rejecting configurations the server must never issue.
	 *
	 * @throws ApiException {@code LISA_NOT_ALLOWED} if Lisa is requested, or {@code BAD_REQUEST} if the
	 *   variant is unsupported at that size
	 */
	public static @NonNull PuzzleKey key(@NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty, long seed) {
		requireMultiplayerSafe(difficulty);
		return singlePlayerKey(size, variant, difficulty, seed);
	}
	
	/**
	 * Builds a key for content only one player ever solves, where Lisa is an ordinary tier.
	 * <p>
	 * The daily is exactly that: one grid per player per day, scored against nobody. {@link #key} refuses
	 * Lisa because a match is a shared board and Lisa's gameplay modifiers have no agreed meaning across
	 * two clients - a constraint about multiplayer, which the daily was only ever caught by because it
	 * happened to reuse the same builder. It is selectable as a daily tier on the client, {@code stats}
	 * has always accepted it, and rejecting it here left those players unable to score a daily at all.
	 *
	 * @throws ApiException {@code BAD_REQUEST} if the variant is unsupported at that size
	 */
	public static @NonNull PuzzleKey singlePlayerKey(@NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty, long seed) {
		if (!variant.isSupportedAt(size)) {
			throw ApiException.badRequest(variant + " is not supported at " + size.n() + "x" + size.n());
		}
		return PuzzleKey.of(size, variant, difficulty, seed);
	}
	
	/**
	 * Rejects Lisa, which carries single-player gameplay modifiers and so has no agreed meaning on a board
	 * two clients share (server-spec 10.1, 16).
	 */
	public static void requireMultiplayerSafe(@NonNull Difficulty difficulty) {
		if (difficulty.isLisa()) {
			throw new ApiException(ErrorCode.LISA_NOT_ALLOWED, "Lisa is a single-player difficulty");
		}
	}
	
	/**
	 * Parses a {@code 1}-{@link Difficulty#LISA} difficulty index from a request for shared content.
	 * <p>
	 * The whole range is a real tier, Lisa included, but Lisa is then refused for shared content by
	 * {@link #requireMultiplayerSafe} - so index {@link Difficulty#LISA} is a recognised tier that is not
	 * allowed here, which is why it answers {@code LISA_NOT_ALLOWED} rather than {@code BAD_REQUEST}.
	 *
	 * @param index The tier index from the request
	 * @return The matching tier, never Lisa
	 * @throws ApiException {@code LISA_NOT_ALLOWED} for Lisa's index, {@code BAD_REQUEST} for anything
	 *   outside the range
	 */
	public static @NonNull Difficulty difficultyOfIndex(int index) {
		Difficulty difficulty;
		try {
			difficulty = Difficulty.ofIndex(index);
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest(outOfRange(index));
		}
		requireMultiplayerSafe(difficulty);
		return difficulty;
	}
	
	/**
	 * Parses a {@code 1}-{@link Difficulty#LISA} difficulty index from a request for single-player content -
	 * see {@link #singlePlayerKey}.
	 *
	 * @param index The tier index from the request
	 * @return The matching tier, Lisa included
	 * @throws ApiException {@code BAD_REQUEST} if the index names no tier
	 */
	public static @NonNull Difficulty singlePlayerDifficultyOfIndex(int index) {
		try {
			return Difficulty.ofIndex(index);
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest(outOfRange(index));
		}
	}
	
	/**
	 * The one place the accepted range is worded, derived from {@link Difficulty#LISA} rather than written
	 * out - the scale went from six tiers to fifteen, and a hard-coded bound is exactly what left the old
	 * message telling clients "1 and 5" while the code accepted more.
	 *
	 * @param index The rejected index
	 * @return The message to answer with
	 */
	private static @NonNull String outOfRange(int index) {
		return "difficulty must be between 1 and " + Difficulty.LISA.index() + ", got: " + index;
	}
}
