package net.luis.sudoku.handler;

import net.luis.sudoku.compat.LegacyDifficulty;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.generation.PuzzleGenerator;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.key.PuzzleKey;
import net.luis.sudoku.sharecode.GivensCodec;
import net.luis.sudoku.support.HttpTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for the match routes at both API versions, and for the {@code matches.givens} column behind
 * the v2 shape.
 */
class MatchRoutesTest extends HttpTest {
	
	private static String createBody(int difficulty) {
		return "{\"mode\": \"RACE\", \"config\": {\"size\": 9, \"variant\": \"CLASSIC\", \"difficulty\": " + difficulty + "}}";
	}
	
	private String createMatch(String token, String path, int difficulty) {
		Response created = this.post(token, path, createBody(difficulty));
		assertEquals(201, created.status(), created.body());
		return created.at("matchId").asString();
	}
	
	// --- creation ---
	
	@Test
	void createMatch_atV1_readsTheSixTierInteger() {
		// A v1 client asking for its hardest ordinary tier means band 13, not band 5.
		String token = this.register("Owner");
		
		String matchId = this.createMatch(token, "/api/v1/matches", 5);
		
		Match stored = this.services.matchService().get(UUID.fromString(matchId));
		assertEquals(Difficulty.THIRTEEN, stored.difficulty());
	}
	
	@Test
	void createMatch_atV2_readsTheRealTier() {
		String token = this.register("Owner");
		
		String matchId = this.createMatch(token, "/api/v2/matches", 13);
		
		assertEquals(Difficulty.THIRTEEN, this.services.matchService().get(UUID.fromString(matchId)).difficulty());
	}
	
	@Test
	void createMatch_atV1_withSix_isRefusedAsLisa() {
		String token = this.register("Owner");
		assertEquals(400, this.post(token, "/api/v1/matches", createBody(6)).status());
	}
	
	@Test
	void createMatch_atV1_withSeven_isRefusedAsOutOfRange() {
		// 7 is a real band a v2 client may ask for and a value v1 never had.
		String token = this.register("Owner");
		assertEquals(400, this.post(token, "/api/v1/matches", createBody(7)).status());
	}
	
	@Test
	void createMatch_atV2_acrossTheWidenedRange_acceptsOneThroughFourteen() {
		String token = this.register("Owner");
		
		assertAll(
			() -> assertEquals(400, this.post(token, "/api/v2/matches", createBody(0)).status(), "0"),
			() -> assertEquals(201, this.post(token, "/api/v2/matches", createBody(1)).status(), "1"),
			() -> assertEquals(201, this.post(token, "/api/v2/matches", createBody(14)).status(), "14"),
			() -> assertEquals(400, this.post(token, "/api/v2/matches", createBody(15)).status(), "15 is Lisa"),
			() -> assertEquals(400, this.post(token, "/api/v2/matches", createBody(16)).status(), "16")
		);
	}
	
	// --- reading a match back ---
	
	@Test
	void getMatch_atV1_carriesTheSixTierIntegerAndNoGivens() {
		String token = this.register("Owner");
		String matchId = this.createMatch(token, "/api/v2/matches", 13);
		
		JsonNode key = this.get(token, "/api/v1/matches/" + matchId).at("puzzleKey");
		
		assertAll(
			() -> assertEquals(LegacyDifficulty.toLegacy(Difficulty.THIRTEEN), key.get("difficulty").asInt()),
			() -> assertNull(key.get("givens"))
		);
	}
	
	@Test
	void getMatch_atV2_carriesTheRealTierAndGivens() {
		String token = this.register("Owner");
		String matchId = this.createMatch(token, "/api/v2/matches", 13);
		
		Response response = this.get(token, "/api/v2/matches/" + matchId);
		
		JsonNode puzzle = response.at("puzzle");
		assertAll(
			() -> assertEquals(200, response.status()),
			() -> assertEquals(13, puzzle.get("difficulty").asInt()),
			() -> assertFalse(puzzle.get("givens").asString().isBlank()),
			() -> assertNull(response.at("puzzleKey"))
		);
	}
	
	@Test
	void getMatch_atV2_shipsGivensThatDecodeBackToTheStoredPuzzle() {
		String token = this.register("Owner");
		String matchId = this.createMatch(token, "/api/v2/matches", 2);
		
		JsonNode puzzle = this.get(token, "/api/v2/matches/" + matchId).at("puzzle");
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
	void activeMatch_atBothVersions_answers204WhenThereIsNone() {
		String token = this.register("Owner");
		
		assertAll(
			() -> assertEquals(204, this.get(token, "/api/v1/matches/active").status()),
			() -> assertEquals(204, this.get(token, "/api/v2/matches/active").status())
		);
	}
	
	// --- the givens column ---
	
	@Test
	void createMatch_storesTheGivensOnTheRow() {
		String token = this.register("Owner");
		String matchId = this.createMatch(token, "/api/v2/matches", 2);
		
		Match stored = this.services.matchService().get(UUID.fromString(matchId));
		
		assertAll(
			() -> assertNotNull(stored.givens(), "a match created today always carries its givens"),
			() -> assertEquals(GridSize.NINE.cellCount(), GivensCodec.decode(stored.givens()).length)
		);
	}
	
	@Test
	void puzzle_fromAStoredRow_roundTripsThroughTheGivensColumn() {
		String token = this.register("Owner");
		String matchId = this.createMatch(token, "/api/v2/matches", 2);
		Match stored = this.services.matchService().get(UUID.fromString(matchId));
		
		assertEquals(
			PuzzleGenerator.fromGivens(stored.key(), GivensCodec.decode(stored.givens())).puzzle(),
			this.services.matchService().puzzle(stored).puzzle()
		);
	}
	
	@Test
	void puzzle_forARowWithNoGivens_fallsBackToRegeneratingFromTheKey() {
		// Every match created before this deploy has a null givens column, and must still rebuild.
		String token = this.register("Owner");
		String matchId = this.createMatch(token, "/api/v2/matches", 2);
		Match stored = this.services.matchService().get(UUID.fromString(matchId));
		
		Match legacyRow = new Match(
			stored.id(), stored.mode(), stored.state(), stored.creatorId(), stored.size(), stored.variant(),
			stored.difficulty(), stored.seed(), null, stored.livesEnabled(), stored.hintsEnabled(), stored.stake(),
			stored.inviteToken(), stored.winnerId(), stored.endReason(), stored.createdAt(), stored.startedAt(), stored.endedAt()
		);
		
		assertEquals(
			this.services.matchService().puzzle(stored).puzzle(),
			this.services.matchService().puzzle(legacyRow).puzzle(),
			"the fallback must produce the same grid the stored givens describe"
		);
	}
}
