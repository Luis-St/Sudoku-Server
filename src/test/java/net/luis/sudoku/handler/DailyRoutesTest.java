package net.luis.sudoku.handler;

import net.luis.sudoku.compat.LegacyDifficulty;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.generation.PuzzleGenerator;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.key.PuzzleKey;
import net.luis.sudoku.sharecode.GivensCodec;
import net.luis.sudoku.support.HttpTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for the daily routes at both API versions.
 * <p>
 * The rule from {@code ApiVersion}: a contract change registers the route again under the next version and
 * leaves the old path registered and working. These tests hold both halves of that at once - a v1 client
 * still sees a six-tier integer and no givens, a v2 client sees the real 1-15 index and givens that decode
 * back to the very same puzzle.
 */
class DailyRoutesTest extends HttpTest {
	
	@Test
	void daily_atV1_carriesTheSixTierIntegerAndNoGivens() {
		String token = this.register("Owner");
		
		Response response = this.get(token, "/api/v1/daily");
		
		JsonNode key = response.at("puzzleKey");
		assertAll(
			() -> assertEquals(200, response.status()),
			() -> assertNotNull(key),
			// The stored default is tier 5 of fifteen, which reduces to legacy 2.
			() -> assertEquals(LegacyDifficulty.toLegacy(Difficulty.FIVE), key.get("difficulty").asInt()),
			() -> assertNull(key.get("givens"), "a v1 client has no field to put them in"),
			() -> assertNull(response.at("puzzle"), "the v2 field must not appear on the v1 path")
		);
	}
	
	@Test
	void daily_atV2_carriesTheRealTierAndGivens() {
		String token = this.register("Owner");
		
		Response response = this.get(token, "/api/v2/daily");
		
		JsonNode puzzle = response.at("puzzle");
		assertAll(
			() -> assertEquals(200, response.status()),
			() -> assertNotNull(puzzle),
			() -> assertEquals(Difficulty.FIVE.index(), puzzle.get("difficulty").asInt()),
			() -> assertFalse(puzzle.get("givens").asString().isBlank()),
			() -> assertNull(response.at("puzzleKey"), "the v1 field is replaced, not kept alongside")
		);
	}
	
	@Test
	void daily_atBothVersions_describesTheSameGrid() {
		// The whole point: the two shapes are two views of one puzzle, not two puzzles.
		String token = this.register("Owner");
		
		JsonNode v1 = this.get(token, "/api/v1/daily").at("puzzleKey");
		JsonNode v2 = this.get(token, "/api/v2/daily").at("puzzle");
		
		assertAll(
			() -> assertEquals(v1.get("seed").asString(), v2.get("seed").asString()),
			() -> assertEquals(v1.get("size").asInt(), v2.get("size").asInt()),
			() -> assertEquals(v1.get("variant").asString(), v2.get("variant").asString()),
			() -> assertEquals(v1.get("genVersion").asInt(), v2.get("genVersion").asInt())
		);
	}
	
	@Test
	void daily_atV2_shipsGivensThatDecodeBackToTheGeneratedPuzzle() {
		String token = this.register("Owner");
		
		JsonNode puzzle = this.get(token, "/api/v2/daily").at("puzzle");
		
		PuzzleKey key = new PuzzleKey(
			puzzle.get("genVersion").asInt(),
			GridSize.ofEdgeLength(puzzle.get("size").asInt()),
			net.luis.sudoku.grid.Variant.valueOf(puzzle.get("variant").asString()),
			Difficulty.ofIndex(puzzle.get("difficulty").asInt()),
			Long.parseLong(puzzle.get("seed").asString())
		);
		int[] givens = GivensCodec.decode(puzzle.get("givens").asString());
		
		assertEquals(PuzzleGenerator.generate(key).puzzle(), PuzzleGenerator.fromGivens(key, givens).puzzle(),
			"the shipped digits must rebuild the grid the key generates");
	}
	
	// --- preferences ---
	
	@Test
	void preferences_setAtV2_readBackAtV1_isReducedToTheLegacyScale() {
		String token = this.register("Owner");
		
		this.put(token, "/api/v2/preferences", "{\"dailyDifficulty\": 13}");
		
		assertAll(
			() -> assertEquals(13, this.get(token, "/api/v2/preferences").at("dailyDifficulty").asInt()),
			() -> assertEquals(5, this.get(token, "/api/v1/preferences").at("dailyDifficulty").asInt())
		);
	}
	
	@Test
	void preferences_setAtV1_areStoredAsTheRealTier() {
		// A v1 client says 3 and means "the middle one"; the server stores the band that names.
		String token = this.register("Owner");
		
		this.put(token, "/api/v1/preferences", "{\"dailyDifficulty\": 3}");
		
		assertAll(
			() -> assertEquals(3, this.get(token, "/api/v1/preferences").at("dailyDifficulty").asInt()),
			() -> assertEquals(Difficulty.SEVEN.index(), this.get(token, "/api/v2/preferences").at("dailyDifficulty").asInt())
		);
	}
	
	@Test
	void preferences_atV1_aboveSix_isRejected() {
		// 7 is a real tier and still nonsense from a client that only ever had six.
		String token = this.register("Owner");
		assertEquals(400, this.put(token, "/api/v1/preferences", "{\"dailyDifficulty\": 7}").status());
	}
	
	@Test
	void preferences_atV2_acrossTheWidenedRange_acceptsOneThroughFifteen() {
		String token = this.register("Owner");
		
		assertAll(
			() -> assertEquals(400, this.put(token, "/api/v2/preferences", "{\"dailyDifficulty\": 0}").status(), "0"),
			() -> assertEquals(200, this.put(token, "/api/v2/preferences", "{\"dailyDifficulty\": 1}").status(), "1"),
			() -> assertEquals(200, this.put(token, "/api/v2/preferences", "{\"dailyDifficulty\": 14}").status(), "14"),
			() -> assertEquals(200, this.put(token, "/api/v2/preferences", "{\"dailyDifficulty\": 15}").status(), "15"),
			() -> assertEquals(400, this.put(token, "/api/v2/preferences", "{\"dailyDifficulty\": 16}").status(), "16")
		);
	}
	
	// --- leaderboard ---
	
	@Test
	void leaderboard_atBothVersions_acceptsItsOwnScaleAndRefusesTheOthers() {
		String token = this.register("Owner");
		
		assertAll(
			() -> assertEquals(200, this.get(token, "/api/v1/daily/leaderboard?difficulty=6").status(), "v1 6 is Lisa"),
			() -> assertEquals(400, this.get(token, "/api/v1/daily/leaderboard?difficulty=7").status(), "v1 has no 7"),
			() -> assertEquals(200, this.get(token, "/api/v2/daily/leaderboard?difficulty=15").status(), "v2 15 is Lisa"),
			() -> assertEquals(400, this.get(token, "/api/v2/daily/leaderboard?difficulty=16").status(), "v2 stops at 15")
		);
	}
}
