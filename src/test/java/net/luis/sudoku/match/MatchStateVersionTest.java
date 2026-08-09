package net.luis.sudoku.match;

import net.luis.sudoku.compat.LegacyDifficulty;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.generation.PuzzleGenerator;
import net.luis.sudoku.match.support.*;
import net.luis.sudoku.sharecode.GivensCodec;
import org.junit.jupiter.api.*;

import java.util.Map;

import static net.luis.sudoku.match.support.MatchFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for the versioned {@code MATCH_STATE} payload.
 * <p>
 * {@code /ws/v1/matches/{id}} and {@code /ws/v2/matches/{id}} serve the same live match, so a v1 and a v2
 * socket can be attached to one board at the same time - which is exactly the case a per-match version
 * would get wrong.
 */
class MatchStateVersionTest {
	
	private GeneratedPuzzle puzzle;
	private RaceMatch race;
	
	@BeforeEach
	void createMatch() {
		this.puzzle = MatchFixture.puzzle();
		Match match = MatchFixture.match(MatchMode.RACE, false, 0, this.puzzle);
		this.race = new RaceMatch(match, this.puzzle, MatchFixture.matchConfig(), new RecordingCallbacks());
	}
	
	@AfterEach
	void shutdown() {
		if (this.race != null) {
			this.race.shutdown();
		}
	}
	
	private Map<?, ?> keySentTo(FakeConnection connection) {
		connect(this.race, connection);
		MessageEnvelope state = connection.lastOf(MessageType.MATCH_STATE);
		assertNotNull(state, "every connect gets a snapshot");
		return (Map<?, ?>) state.payloadOrEmpty().get("puzzleKey");
	}
	
	@Test
	void matchState_onAV2Socket_carriesTheRealTierAndGivens() {
		Map<?, ?> key = this.keySentTo(FakeConnection.of("Alice", 2));
		
		assertAll(
			() -> assertEquals(Difficulty.TWO.index(), key.get("difficulty")),
			() -> assertEquals(GivensCodec.encode(this.puzzle.puzzle()), key.get("givens"))
		);
	}
	
	@Test
	void matchState_onAV1Socket_carriesTheSixTierIntegerAndNoGivens() {
		Map<?, ?> key = this.keySentTo(FakeConnection.of("Alice", 1));
		
		assertAll(
			() -> assertEquals(LegacyDifficulty.toLegacy(Difficulty.TWO), key.get("difficulty")),
			() -> assertFalse(key.containsKey("givens"), "a v1 client has no field to put them in")
		);
	}
	
	@Test
	void matchState_onAV1Socket_keepsEverythingElseIntact() {
		Map<?, ?> key = this.keySentTo(FakeConnection.of("Alice", 1));
		
		assertAll(
			() -> assertEquals(MatchFixture.SIZE.n(), key.get("size")),
			() -> assertEquals("CLASSIC", key.get("variant")),
			() -> assertEquals(Long.toString(this.puzzle.key().seed()), key.get("seed")),
			() -> assertEquals(this.puzzle.key().genVersion(), key.get("genVersion"))
		);
	}
	
	@Test
	void matchState_forTwoSocketsOnDifferentVersions_isShapedPerConnection() {
		// One player on an old build and one on a new build in the same match: the version belongs to the
		// socket, not to the board.
		FakeConnection legacy = FakeConnection.of("Alice", 1);
		FakeConnection current = FakeConnection.of("Bob", 2);
		
		Map<?, ?> legacyKey = this.keySentTo(legacy);
		Map<?, ?> currentKey = this.keySentTo(current);
		
		assertAll(
			() -> assertFalse(legacyKey.containsKey("givens")),
			() -> assertTrue(currentKey.containsKey("givens")),
			() -> assertNotEquals(legacyKey.get("difficulty"), currentKey.get("difficulty"))
		);
	}
	
	@Test
	void matchState_givens_decodeBackToTheBoardBeingPlayed() {
		Map<?, ?> key = this.keySentTo(FakeConnection.of("Alice", 2));
		
		int[] givens = GivensCodec.decode((String) key.get("givens"));
		
		assertEquals(this.puzzle.puzzle(), PuzzleGenerator.fromGivens(this.puzzle.key(), givens).puzzle());
	}
}
