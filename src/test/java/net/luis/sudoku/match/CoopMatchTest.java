package net.luis.sudoku.match;

import net.luis.sudoku.domain.Match;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.match.support.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static net.luis.sudoku.match.support.MatchFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link CoopMatch}, covering server-spec 11.3.
 */
class CoopMatchTest {
	
	private GeneratedPuzzle puzzle;
	private RecordingCallbacks callbacks;
	private CoopMatch coop;
	private FakeConnection alice;
	private FakeConnection bob;
	private FakeConnection carol;
	
	@BeforeEach
	void createMatch() {
		this.puzzle = MatchFixture.puzzle();
		this.callbacks = new RecordingCallbacks();
		this.alice = FakeConnection.of("Alice");
		this.bob = FakeConnection.of("Bob");
		this.carol = FakeConnection.of("Carol");
	}
	
	private void start(boolean livesEnabled, FakeConnection... players) {
		Match match = MatchFixture.match(MatchMode.COOP, livesEnabled, 0, this.puzzle);
		this.coop = new CoopMatch(match, this.puzzle, MatchFixture.matchConfig(), this.callbacks);
		for (FakeConnection player : players) {
			connect(this.coop, player);
		}
		ready(this.coop, players);
	}
	
	@AfterEach
	void shutdown() {
		if (this.coop != null) {
			this.coop.shutdown();
		}
	}
	
	@Test
	void capacity_isFour() {
		assertEquals(4, MatchMode.COOP.capacity());
	}
	
	@Test
	void ready_fromTwoOfUpToFour_startsTheMatch() {
		this.start(false, this.alice, this.bob);
		assertEquals(MatchState.RUNNING, this.coop.state());
	}
	
	@Test
	void ready_withThreeParticipants_startsOnceAllAreReady() {
		this.start(false, this.alice, this.bob, this.carol);
		assertEquals(MatchState.RUNNING, this.coop.state());
	}
	
	@Test
	void place_aCorrectDigit_isBroadcastToEveryone() {
		// The pen layer is shared, unlike race.
		this.start(false, this.alice, this.bob, this.carol);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		this.bob.clear();
		this.carol.clear();
		
		place(this.coop, this.alice, cell, this.puzzle.solutionAt(cell));
		
		assertAll(
			() -> assertTrue(this.bob.sawType(MessageType.BOARD_UPDATE)),
			() -> assertTrue(this.carol.sawType(MessageType.BOARD_UPDATE)),
			() -> assertEquals(this.alice.userId().toString(),
				this.bob.lastOf(MessageType.BOARD_UPDATE).payloadOrEmpty().get("byUser"))
		);
	}
	
	@Test
	void place_intoAnAlreadyFilledCell_isReportedAsAlreadyFilledRatherThanAMistake() {
		// The losing half of a race for one cell. Spec 11.3: brief, non-alarming feedback, and it must
		// not cost a life.
		this.start(true, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		place(this.coop, this.alice, cell, this.puzzle.solutionAt(cell));
		this.bob.clear();
		
		place(this.coop, this.bob, cell, this.puzzle.solutionAt(cell));
		
		MessageEnvelope entry = this.bob.lastOf(MessageType.ENTRY_RESULT);
		assertAll(
			() -> assertNotNull(entry),
			() -> assertEquals(true, entry.payloadOrEmpty().get("alreadyFilled")),
			() -> assertEquals(true, entry.payloadOrEmpty().get("correct")),
			() -> assertEquals(CoopMatch.SHARED_LIVES, entry.payloadOrEmpty().get("livesLeft"),
				"losing the race must not cost a life")
		);
	}
	
	@Test
	void place_concurrentEntriesIntoOneCell_resolveByArrivalOrder() {
		// Everything is serialised through the match queue, so "simultaneous" entries have a definite
		// order and exactly one wins.
		this.start(false, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		int digit = this.puzzle.solutionAt(cell);
		
		this.coop.submit(() -> this.coop.onMessage(this.alice.userId(), MessageType.PLACE,
			Map.of("cell", cell, "digit", digit)));
		this.coop.submit(() -> this.coop.onMessage(this.bob.userId(), MessageType.PLACE,
			Map.of("cell", cell, "digit", digit)));
		MatchFixture.drain(this.coop);
		
		assertAll(
			() -> assertEquals(1, this.coop.filledCount(), "the cell is filled exactly once"),
			() -> assertEquals(1, this.alice.receivedOf(MessageType.BOARD_UPDATE).size(),
				"only one BOARD_UPDATE is emitted")
		);
	}
	
	@Test
	void place_anIncorrectDigit_costsFromTheSharedPool() {
		this.start(true, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		int wrong = MatchFixture.wrongDigitFor(this.puzzle, cell);
		
		place(this.coop, this.alice, cell, wrong);
		place(this.coop, this.bob, cell, wrong);
		
		// A single pool of five for the group, not five each (spec 11.3).
		assertEquals(CoopMatch.SHARED_LIVES - 2, this.coop.sharedLivesLeft());
	}
	
	@Test
	void place_exhaustingTheSharedPool_endsTheMatch() {
		this.start(true, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		int wrong = MatchFixture.wrongDigitFor(this.puzzle, cell);
		
		for (int attempt = 0; attempt < CoopMatch.SHARED_LIVES; attempt++) {
			place(this.coop, this.alice, cell, wrong);
		}
		
		RecordingCallbacks.Ended ended = this.callbacks.ended();
		assertAll(
			() -> assertNotNull(ended),
			() -> assertEquals(EndReason.LIVES_EXHAUSTED, ended.reason()),
			() -> assertNull(ended.winnerId())
		);
	}
	
	@Test
	void place_completingTheGrid_endsWithNoSingleWinner() {
		this.start(false, this.alice, this.bob);
		List<Integer> holes = MatchFixture.holes(this.puzzle);
		
		for (int index = 0; index < holes.size(); index++) {
			// Alternate so both players genuinely contribute.
			FakeConnection player = index % 2 == 0 ? this.alice : this.bob;
			place(this.coop, player, holes.get(index), this.puzzle.solutionAt(holes.get(index)));
		}
		
		RecordingCallbacks.Ended ended = this.callbacks.ended();
		assertAll(
			() -> assertNotNull(ended),
			() -> assertEquals(EndReason.COMPLETED, ended.reason()),
			() -> assertNull(ended.winnerId(), "everyone wins together")
		);
	}
	
	@Test
	void presence_isBroadcastToTheGroup() {
		this.start(false, this.alice, this.bob, this.carol);
		this.bob.clear();
		this.carol.clear();
		
		send(this.coop, this.alice, MessageType.PRESENCE, Map.of("cell", 3));
		
		assertAll(
			() -> assertTrue(this.bob.sawType(MessageType.PRESENCE)),
			() -> assertEquals(3, this.bob.lastOf(MessageType.PRESENCE).payloadOrEmpty().get("cell")),
			() -> assertEquals(this.alice.userId().toString(),
				this.carol.lastOf(MessageType.PRESENCE).payloadOrEmpty().get("userId"))
		);
	}
	
	@Test
	void presence_withAnInvalidCell_isIgnored() {
		this.start(false, this.alice, this.bob);
		this.bob.clear();
		
		send(this.coop, this.alice, MessageType.PRESENCE, Map.of("cell", 9999));
		
		assertFalse(this.bob.sawType(MessageType.PRESENCE));
	}
	
	@Test
	void matchState_carriesTheSharedBoardPresenceAndLives() {
		this.start(true, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		place(this.coop, this.alice, cell, this.puzzle.solutionAt(cell));
		send(this.coop, this.alice, MessageType.PRESENCE, Map.of("cell", cell));
		this.carol.clear();
		
		connect(this.coop, this.carol);
		
		MessageEnvelope state = this.carol.lastOf(MessageType.MATCH_STATE);
		assertAll(
			() -> assertNotNull(state),
			() -> assertTrue(state.payloadOrEmpty().containsKey("board")),
			() -> assertTrue(state.payloadOrEmpty().containsKey("presence")),
			() -> assertEquals(CoopMatch.SHARED_LIVES, state.payloadOrEmpty().get("livesLeft"))
		);
	}
	
	@Test
	void resign_withThreePlayers_leavesTheMatchRunning() {
		this.start(false, this.alice, this.bob, this.carol);
		
		send(this.coop, this.carol, MessageType.RESIGN, Map.of());
		
		assertNull(this.callbacks.ended(), "two players can carry on");
	}
	
	@Test
	void resign_droppingBelowTwoPlayers_endsTheMatch() {
		this.start(false, this.alice, this.bob);
		
		send(this.coop, this.bob, MessageType.RESIGN, Map.of());
		
		RecordingCallbacks.Ended ended = this.callbacks.ended();
		assertAll(
			() -> assertNotNull(ended),
			() -> assertEquals(EndReason.RESIGNED, ended.reason())
		);
	}
	
	@Test
	void note_isNeverBroadcast() {
		this.start(false, this.alice, this.bob);
		this.bob.clear();
		
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", 0, "digit", 1, "add", true));
		
		assertTrue(this.bob.received().isEmpty(), "pencil layers stay private");
	}
	
	@Test
	void onMessage_aDuelOnlyType_isRejected() {
		this.start(false, this.alice, this.bob);
		this.alice.clear();
		
		send(this.coop, this.alice, MessageType.BACKGROUNDED, Map.of());
		
		assertEquals("UNSUPPORTED", this.alice.lastOf(MessageType.ERROR).payloadOrEmpty().get("error"));
	}
}
