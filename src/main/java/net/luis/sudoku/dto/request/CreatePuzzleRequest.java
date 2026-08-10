package net.luis.sudoku.dto.request;

import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.difficulty.DifficultyBands;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.puzzle.PuzzleFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Body of {@code POST /api/v2/puzzles}: a single-player puzzle the client would otherwise have to
 * generate itself.
 *
 * @param size grid edge length
 * @param variant {@code CLASSIC} or {@code CHAOS}
 * @param difficulty tier index 1-15; this is single-player content, so Lisa is an ordinary tier here
 */
public record CreatePuzzleRequest(@Nullable Integer size, @Nullable String variant, @Nullable Integer difficulty) {
	
	/**
	 * @return The requested grid size
	 * @throws ApiException {@code BAD_REQUEST} if the field is missing or names no supported size
	 */
	public @NonNull GridSize requireSize() {
		if (this.size == null) {
			throw ApiException.badRequest("Missing required field: size");
		}
		
		try {
			return GridSize.ofEdgeLength(this.size);
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("size must be 4, 6, 9, 12 or 16, got: " + this.size);
		}
	}
	
	/**
	 * Resolves the requested variant and checks the size supports it - {@code CHAOS} needs at least a 6x6
	 * grid, and letting an unsupported pair through would surface as a 500 from the key constructor.
	 *
	 * @param size The size the variant has to be supported at
	 * @return The requested variant
	 * @throws ApiException {@code BAD_REQUEST} if the field is missing, names no variant, or names one this
	 *   size does not support
	 */
	public @NonNull Variant requireVariant(@NonNull GridSize size) {
		String value = Requests.require(this.variant, "variant");
		
		Variant variant;
		try {
			variant = Variant.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("variant must be CLASSIC or CHAOS, got: " + value);
		}
		
		if (!variant.isSupportedAt(size)) {
			throw ApiException.badRequest(variant + " is not supported at " + size.n() + "x" + size.n());
		}
		return variant;
	}
	
	/**
	 * Resolves the requested tier and holds it to the bands the size can actually produce.
	 * <p>
	 * <strong>Rejected rather than snapped.</strong> {@code DifficultyBands.nearestSupported} would quietly
	 * hand back the closest reachable band, and a 4x4 grid reaches exactly one - so a player asking for tier
	 * 12 on a 4x4 would be served a trivial puzzle and told it was tier 12. The reachable set is a fact about
	 * the grid the client can render a picker from, so the honest answer is to name it and let the client
	 * ask again.
	 *
	 * <strong>The variant is part of the question.</strong> A 16x16 jigsaw reaches band 8 and no further while
	 * a 16x16 classic reaches all fifteen, so "is tier 13 reachable at 16x16" has no answer until the layout
	 * is known - and answering it from the size alone is what used to hand a chaos player a band-8 grid
	 * labelled 13.
	 *
	 * @param size The size the tier has to be reachable at
	 * @param variant The layout the tier has to be reachable in
	 * @return The requested tier
	 * @throws ApiException {@code BAD_REQUEST} if the field is missing, names no tier, or names a band this
	 *   size and variant cannot produce
	 */
	public @NonNull Difficulty requireDifficulty(@NonNull GridSize size, @NonNull Variant variant) {
		if (this.difficulty == null) {
			throw ApiException.badRequest("Missing required field: difficulty");
		}

		Difficulty requested = PuzzleFactory.singlePlayerDifficultyOfIndex(this.difficulty);
		Set<Difficulty> supported = DifficultyBands.defaults().supported(size, variant);
		if (!supported.contains(requested)) {
			throw ApiException.badRequest("difficulty " + requested.index() + " is not reachable for " + variant + " at " + size.n() + "x" + size.n()
				+ "; supported: " + supported.stream().map(band -> String.valueOf(band.index())).toList());
		}
		return requested;
	}
}
