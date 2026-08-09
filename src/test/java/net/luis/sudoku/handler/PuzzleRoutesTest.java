package net.luis.sudoku.handler;

import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.generation.PuzzleGenerator;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.key.PuzzleKey;
import net.luis.sudoku.sharecode.GivensCodec;
import net.luis.sudoku.support.HttpTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@code POST /api/v2/puzzles}: server-side generation of single-player puzzles.
 */
class PuzzleRoutesTest extends HttpTest {
	
	private static String body(int size, String variant, int difficulty) {
		return "{\"size\": " + size + ", \"variant\": \"" + variant + "\", \"difficulty\": " + difficulty + "}";
	}
	
	@Test
	void createPuzzle_returnsAPuzzleWithGivens() {
		String token = this.register("Owner");
		
		Response response = this.post(token, "/api/v2/puzzles", body(9, "CLASSIC", 7));
		
		JsonNode puzzle = response.at("puzzle");
		assertAll(
			() -> assertEquals(200, response.status(), response.body()),
			() -> assertEquals(9, puzzle.get("size").asInt()),
			() -> assertEquals("CLASSIC", puzzle.get("variant").asString()),
			() -> assertEquals(7, puzzle.get("difficulty").asInt()),
			() -> assertFalse(puzzle.get("givens").asString().isBlank())
		);
	}
	
	@Test
	void createPuzzle_shipsGivensThatRebuildTheKeysPuzzle() {
		String token = this.register("Owner");
		
		JsonNode puzzle = this.post(token, "/api/v2/puzzles", body(9, "CLASSIC", 2)).at("puzzle");
		
		PuzzleKey key = new PuzzleKey(
			puzzle.get("genVersion").asInt(),
			GridSize.ofEdgeLength(puzzle.get("size").asInt()),
			Variant.valueOf(puzzle.get("variant").asString()),
			Difficulty.ofIndex(puzzle.get("difficulty").asInt()),
			Long.parseLong(puzzle.get("seed").asString())
		);
		
		assertEquals(
			PuzzleGenerator.generate(key).puzzle(),
			PuzzleGenerator.fromGivens(key, GivensCodec.decode(puzzle.get("givens").asString())).puzzle()
		);
	}
	
	@Test
	void createPuzzle_withLisa_isAllowed() {
		// Single-player content, so Lisa is an ordinary tier here - and the queue no longer refuses it.
		String token = this.register("Owner");
		
		Response response = this.post(token, "/api/v2/puzzles", body(9, "CLASSIC", Difficulty.LISA.index()));
		
		assertAll(
			() -> assertEquals(200, response.status(), response.body()),
			() -> assertEquals(Difficulty.LISA.index(), response.at("puzzle", "difficulty").asInt())
		);
	}
	
	@Test
	void createPuzzle_atABandTheSizeCannotProduce_isRejectedWithTheSupportedSet() {
		// A 4x4 grid reaches band 1 and nothing else. Naming the reachable bands is the whole point: the
		// client is being told what it may ask for rather than quietly handed something else.
		String token = this.register("Owner");
		
		Response response = this.post(token, "/api/v2/puzzles", body(4, "CLASSIC", 12));
		
		assertAll(
			() -> assertEquals(400, response.status()),
			() -> assertTrue(response.body().contains("not reachable"), response.body()),
			() -> assertTrue(response.body().contains("supported"), response.body())
		);
	}
	
	@Test
	void createPuzzle_atABandInsideAGap_isRejectedRatherThanSnapped() {
		// 6x6 reaches 1, 2, 3, 7 and 8 - the reachable bands are not an unbroken run, which is exactly why
		// this checks the set rather than a ceiling.
		String token = this.register("Owner");
		
		assertAll(
			() -> assertEquals(400, this.post(token, "/api/v2/puzzles", body(6, "CLASSIC", 5)).status(), "6x6 has no band 5"),
			() -> assertEquals(200, this.post(token, "/api/v2/puzzles", body(6, "CLASSIC", 7)).status(), "6x6 does reach band 7")
		);
	}
	
	@Test
	void createPuzzle_atTheBoundariesOfTheWidenedRange_acceptsOneThroughFifteen() {
		String token = this.register("Owner");
		
		assertAll(
			() -> assertEquals(400, this.post(token, "/api/v2/puzzles", body(9, "CLASSIC", 0)).status(), "0"),
			() -> assertEquals(200, this.post(token, "/api/v2/puzzles", body(9, "CLASSIC", 1)).status(), "1"),
			() -> assertEquals(200, this.post(token, "/api/v2/puzzles", body(9, "CLASSIC", 14)).status(), "14"),
			() -> assertEquals(400, this.post(token, "/api/v2/puzzles", body(9, "CLASSIC", 16)).status(), "16")
		);
	}
	
	@Test
	void createPuzzle_withAnUnsupportedVariantForTheSize_isRejected() {
		String token = this.register("Owner");
		assertEquals(400, this.post(token, "/api/v2/puzzles", body(4, "CHAOS", 1)).status());
	}
	
	@Test
	void createPuzzle_hasNoV1Path() {
		// There was never a v1 of this route: a v1 client generates its own puzzles.
		String token = this.register("Owner");
		assertEquals(404, this.post(token, "/api/v1/puzzles", body(9, "CLASSIC", 7)).status());
	}
}
