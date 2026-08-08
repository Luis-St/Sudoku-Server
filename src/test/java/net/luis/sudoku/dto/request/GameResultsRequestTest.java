package net.luis.sudoku.dto.request;

import net.luis.sudoku.dto.request.GameResultsRequest.PlayedGameBody;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.stats.StatsService.PlayedGame;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link GameResultsRequest}, the body of {@code POST /api/v1/stats/games}
 * (server-spec 9).
 */
class GameResultsRequestTest {
	
	private static final String GAME_ID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
	
	private static PlayedGameBody body(String gameId, Integer size, String variant, Integer difficulty) {
		return new PlayedGameBody(gameId, size, variant, difficulty, true, 42_000L, 1);
	}
	
	@Test
	void parseGames_readsEveryFieldOfAGame() {
		GameResultsRequest request = new GameResultsRequest(List.of(body(GAME_ID, 9, "CLASSIC", 3)));
		
		PlayedGame game = request.parseGames().getFirst();
		assertAll(
			() -> assertEquals(UUID.fromString(GAME_ID), game.gameId()),
			() -> assertEquals(9, game.size()),
			() -> assertEquals("CLASSIC", game.variant()),
			() -> assertEquals(3, game.difficulty()),
			() -> assertTrue(game.solved()),
			() -> assertEquals(42_000L, game.elapsedMs()),
			() -> assertEquals(1, game.hintsUsed())
		);
	}
	
	@Test
	void parseGames_keepsTheOrderItWasSentIn() {
		String second = "3f2504e0-4f89-11d3-9a0c-0305e82c3302";
		GameResultsRequest request = new GameResultsRequest(List.of(body(GAME_ID, 9, "CLASSIC", 3), body(second, 4, "CHAOS", 1)));
		
		List<PlayedGame> games = request.parseGames();
		assertAll(
			() -> assertEquals(2, games.size()),
			() -> assertEquals(UUID.fromString(GAME_ID), games.getFirst().gameId()),
			() -> assertEquals(UUID.fromString(second), games.get(1).gameId())
		);
	}
	
	@Test
	void parseGames_upperCasesAndTrimsTheVariant() {
		// The client sends an enum name; accepting the same name in another case is free and one fewer
		// way for a request to be refused for nothing.
		GameResultsRequest request = new GameResultsRequest(List.of(body(GAME_ID, 9, " classic ", 3)));
		
		assertEquals("CLASSIC", request.parseGames().getFirst().variant());
	}
	
	@Test
	void parseGames_aNullGamesList_isAnEmptyUpload() {
		assertEquals(List.of(), new GameResultsRequest(null).parseGames());
	}
	
	@Test
	void parseGames_anEmptyGamesList_isAnEmptyUpload() {
		assertEquals(List.of(), new GameResultsRequest(List.of()).parseGames());
	}
	
	@Test
	void parseGames_aNullGame_isRejected() {
		List<PlayedGameBody> games = new ArrayList<>();
		games.add(null);
		
		assertThrows(ApiException.class, () -> new GameResultsRequest(games).parseGames());
	}
	
	@Test
	void parseGames_aMissingGameId_isRejected() {
		GameResultsRequest request = new GameResultsRequest(List.of(body(null, 9, "CLASSIC", 3)));
		assertThrows(ApiException.class, request::parseGames);
	}
	
	@Test
	void parseGames_aGameIdThatIsNotAUuid_isRejected() {
		// Rejected here rather than deeper: an unparseable id cannot be claimed in recorded_games, so
		// accepting it would mean accepting a game that can never be recognised as already recorded.
		GameResultsRequest request = new GameResultsRequest(List.of(body("not-a-uuid", 9, "CLASSIC", 3)));
		assertThrows(ApiException.class, request::parseGames);
	}
	
	@Test
	void parseGames_aMissingSize_isRejected() {
		GameResultsRequest request = new GameResultsRequest(List.of(body(GAME_ID, null, "CLASSIC", 3)));
		assertThrows(ApiException.class, request::parseGames);
	}
	
	@Test
	void parseGames_aMissingVariant_isRejected() {
		GameResultsRequest request = new GameResultsRequest(List.of(body(GAME_ID, 9, null, 3)));
		assertThrows(ApiException.class, request::parseGames);
	}
	
	@Test
	void parseGames_aMissingDifficulty_isRejected() {
		GameResultsRequest request = new GameResultsRequest(List.of(body(GAME_ID, 9, "CLASSIC", null)));
		assertThrows(ApiException.class, request::parseGames);
	}
	
	@Test
	void parseGames_omittedCounters_defaultToNothingRatherThanBeingRejected() {
		// A game with no hints and no time is a real game - one abandoned immediately - and the client
		// leaving those fields off is not an error worth refusing the upload over.
		GameResultsRequest request = new GameResultsRequest(
			List.of(new PlayedGameBody(GAME_ID, 9, "CLASSIC", 3, null, null, null)));
		
		PlayedGame game = request.parseGames().getFirst();
		assertAll(
			() -> assertFalse(game.solved(), "an omitted outcome is not a solve"),
			() -> assertEquals(0L, game.elapsedMs()),
			() -> assertEquals(0, game.hintsUsed())
		);
	}
}
