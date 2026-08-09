package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.dto.request.CreatePuzzleRequest;
import net.luis.sudoku.dto.response.ErrorResponse;
import net.luis.sudoku.dto.response.NewPuzzleResponse;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.permission.Permission;
import net.luis.sudoku.puzzle.PuzzleQueue;
import org.jspecify.annotations.NonNull;

/**
 * Server-side generation of single-player puzzles, {@code v2} only.
 * <p>
 * The point of the release: the client should never have to generate, only fall back to it. Generation
 * under the fifteen-band rater is up to about a second on a desktop JVM and several times that on a phone,
 * paid on every game start; here it is paid once, off a request thread, by a machine that can afford it.
 * <p>
 * Served out of {@link PuzzleQueue}, so a request normally costs a bit-pack rather than a generation, and
 * every band is pooled - Lisa included, which is the most expensive one and therefore exactly the one that
 * most needs pooling.
 */
public class PuzzleHandler {
	
	private final Authentication authentication;
	private final PuzzleQueue puzzles;
	
	public PuzzleHandler(@NonNull Authentication authentication, @NonNull PuzzleQueue puzzles) {
		this.authentication = authentication;
		this.puzzles = puzzles;
	}
	
	@OpenApi(
		summary = "Generate a single-player puzzle",
		description = "Returns the grid as bit-packed Base64 givens alongside the key it was generated from, so the "
			+ "client never has to generate one itself. This is single-player content, so Lisa (15) is an ordinary "
			+ "tier here. A band the requested size cannot produce is refused with 400 naming the bands it can - a "
			+ "4x4 grid reaches band 1 only, and a 6x6 reaches 1, 2, 3, 7 and 8 - rather than being snapped onto the "
			+ "nearest one, because a puzzle silently two bands off the one that was asked for is worse than a "
			+ "rejection the client can act on.",
		operationId = "createPuzzle",
		path = "/api/v2/puzzles",
		methods = HttpMethod.POST,
		tags = "Puzzles",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = CreatePuzzleRequest.class)),
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = NewPuzzleResponse.class)),
			@OpenApiResponse(status = "400", description = "BAD_REQUEST for an unknown size or variant, or a band the "
				+ "size cannot produce", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void create(@NonNull Context ctx) {
		this.authentication.require(ctx, Permission.CAN_PLAY);
		CreatePuzzleRequest request = ctx.bodyAsClass(CreatePuzzleRequest.class);
		
		GridSize size = request.requireSize();
		Variant variant = request.requireVariant(size);
		Difficulty difficulty = request.requireDifficulty(size);
		
		GeneratedPuzzle generated = this.puzzles.take(size, variant, difficulty);
		ctx.json(NewPuzzleResponse.of(generated));
	}
}
