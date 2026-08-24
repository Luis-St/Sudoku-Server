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
	
	/**
	 * Multiplayer item 2: a wrong entry is a shared event, so every participant hears about it.
	 * <p>
	 * It used to be sent only to the placer, which desynchronised two things at once: the shared lives pool
	 * had already been decremented, so everybody else's hearts sat at a stale count until a reconnect, and
	 * the only trace of the mistake an onlooker got was the placer's own presence highlight.
	 * </p>
	 */
	@Test
	void place_anIncorrectDigit_isBroadcastWithTheNewSharedLivesCount() {
		this.start(true, this.alice, this.bob, this.carol);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		int wrong = MatchFixture.wrongDigitFor(this.puzzle, cell);
		this.bob.clear();
		this.carol.clear();
		
		place(this.coop, this.alice, cell, wrong);
		
		MessageEnvelope entry = this.bob.lastOf(MessageType.ENTRY_RESULT);
		assertAll(
			() -> assertNotNull(entry, "a wrong entry has to reach the other participants"),
			() -> assertEquals(cell, entry.payloadOrEmpty().get("cell")),
			() -> assertEquals(wrong, entry.payloadOrEmpty().get("digit")),
			() -> assertEquals(false, entry.payloadOrEmpty().get("correct")),
			() -> assertEquals(this.alice.userId().toString(), entry.payloadOrEmpty().get("byUser")),
			() -> assertEquals(CoopMatch.SHARED_LIVES - 1, entry.payloadOrEmpty().get("livesLeft"),
				"the shared pool everyone draws from has to travel with it"),
			() -> assertTrue(this.carol.sawType(MessageType.ENTRY_RESULT))
		);
	}
	
	@Test
	void place_anIncorrectDigit_reachesThePlacerExactlyOnce() {
		// The placer is inside a broadcast, so switching from sendTo must not double up their own result.
		this.start(true, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		this.alice.clear();
		
		place(this.coop, this.alice, cell, MatchFixture.wrongDigitFor(this.puzzle, cell));
		
		assertEquals(1, this.alice.receivedOf(MessageType.ENTRY_RESULT).size());
	}
	
	@Test
	void place_aCorrectDigit_keepsTheEntryResultPrivateToThePlacer() {
		// Nothing shared follows from a correct entry beyond the digit itself, and that is the BOARD_UPDATE's
		// job - the result is the placer's own receipt.
		this.start(true, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		this.bob.clear();
		
		place(this.coop, this.alice, cell, this.puzzle.solutionAt(cell));
		
		assertAll(
			() -> assertFalse(this.bob.sawType(MessageType.ENTRY_RESULT)),
			() -> assertTrue(this.bob.sawType(MessageType.BOARD_UPDATE))
		);
	}
	
	@Test
	void place_intoAnAlreadyFilledCell_staysPrivateToTheLoserOfTheRace() {
		// Feedback about your own entry not landing. Nothing happened to the board or the pool, so there is
		// nothing for anyone else to be told.
		this.start(true, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		place(this.coop, this.alice, cell, this.puzzle.solutionAt(cell));
		this.alice.clear();
		
		place(this.coop, this.bob, cell, this.puzzle.solutionAt(cell));
		
		assertFalse(this.alice.sawType(MessageType.ENTRY_RESULT));
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
	void hint_isBroadcastToTheWholeGroup() {
		this.start(false, this.alice, this.bob, this.carol);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		this.bob.clear();
		this.carol.clear();
		
		send(this.coop, this.alice, MessageType.HINT, Map.of("cell", cell));
		
		// The point of the shared offer: everybody marks the same cell, and everybody knows whose it is.
		assertAll(
			() -> assertEquals(cell, this.bob.lastOf(MessageType.HINT).payloadOrEmpty().get("cell")),
			() -> assertEquals(cell, this.carol.lastOf(MessageType.HINT).payloadOrEmpty().get("cell")),
			() -> assertEquals(this.alice.userId().toString(),
				this.carol.lastOf(MessageType.HINT).payloadOrEmpty().get("byUser"))
		);
	}
	
	@Test
	void hint_whileAnotherIsPending_doesNotReplaceIt() {
		this.start(false, this.alice, this.bob);
		List<Integer> holes = MatchFixture.holes(this.puzzle);
		send(this.coop, this.alice, MessageType.HINT, Map.of("cell", holes.get(0)));
		this.bob.clear();
		
		send(this.coop, this.bob, MessageType.HINT, Map.of("cell", holes.get(1)));
		
		// Bob is told what the offer is instead, so his own board still agrees with everybody else's - one
		// player must not move the cell another is deciding about.
		assertAll(
			() -> assertEquals(holes.get(0), this.bob.lastOf(MessageType.HINT).payloadOrEmpty().get("cell")),
			() -> assertEquals(this.alice.userId().toString(),
				this.bob.lastOf(MessageType.HINT).payloadOrEmpty().get("byUser"))
		);
	}
	
	@Test
	void hint_clearedByAnotherPlayer_isWithdrawn() {
		this.start(false, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		send(this.coop, this.alice, MessageType.HINT, Map.of("cell", cell));
		this.alice.clear();
		
		send(this.coop, this.bob, MessageType.HINT, Map.of("clear", true));
		
		// The offer belongs to the group, not to whoever asked: anybody may take it back, and the asker's own
		// board has to follow, since the mark is on every board.
		assertNull(this.alice.lastOf(MessageType.HINT).payloadOrEmpty().get("cell"));
	}
	
	@Test
	void hint_whenTheCellIsFilled_isCleared() {
		this.start(false, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		send(this.coop, this.alice, MessageType.HINT, Map.of("cell", cell));
		this.bob.clear();
		
		place(this.coop, this.alice, cell, this.puzzle.solutionAt(cell));
		
		// Whether it was spent or somebody simply solved it first, a filled cell has nothing left to offer.
		assertNull(this.bob.lastOf(MessageType.HINT).payloadOrEmpty().get("cell"));
	}
	
	@Test
	void matchState_carriesTheSharedBoardHintAndLives() {
		this.start(true, this.alice, this.bob);
		List<Integer> holes = MatchFixture.holes(this.puzzle);
		place(this.coop, this.alice, holes.get(0), this.puzzle.solutionAt(holes.get(0)));
		send(this.coop, this.alice, MessageType.HINT, Map.of("cell", holes.get(1)));
		this.carol.clear();
		
		connect(this.coop, this.carol);
		
		// A player who arrives mid-decision has to see the cell the others are looking at.
		MessageEnvelope state = this.carol.lastOf(MessageType.MATCH_STATE);
		assertAll(
			() -> assertNotNull(state),
			() -> assertTrue(state.payloadOrEmpty().containsKey("board")),
			() -> assertTrue(state.payloadOrEmpty().containsKey("notes")),
			() -> assertEquals(holes.get(1), state.payloadOrEmpty().get("hintCell")),
			() -> assertEquals(this.alice.userId().toString(), state.payloadOrEmpty().get("hintBy")),
			() -> assertEquals(CoopMatch.SHARED_LIVES, state.payloadOrEmpty().get("livesLeft"))
		);
	}
	
	@Test
	void hint_whenItsOwnerDisconnects_isCleared() {
		this.start(false, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		send(this.coop, this.alice, MessageType.HINT, Map.of("cell", cell));
		this.bob.clear();
		
		this.coop.submit(() -> this.coop.onDisconnect(this.alice.userId(), false));
		MatchFixture.drain(this.coop);
		
		// Nobody left can spend or withdraw it, so it would hold the single slot for the rest of the match.
		assertNull(this.bob.lastOf(MessageType.HINT).payloadOrEmpty().get("cell"));
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
	void note_isBroadcastToTheGroup() {
		// Co-op shares its notes, unlike race and duel: the owner asked for pencil marks to be visible to the
		// other players, since four people privately eliminating the same candidate on one board is four
		// people doing the same work. This used to be `case NOTE -> {}`, so a note reached nobody at all.
		this.start(false, this.alice, this.bob, this.carol);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		this.bob.clear();
		this.carol.clear();
		
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", cell, "digit", 1, "add", true));
		
		MessageEnvelope note = this.bob.lastOf(MessageType.NOTE);
		assertAll(
			() -> assertNotNull(note),
			() -> assertEquals(cell, note.payloadOrEmpty().get("cell")),
			() -> assertEquals(1, note.payloadOrEmpty().get("digit")),
			() -> assertEquals(true, note.payloadOrEmpty().get("add")),
			() -> assertEquals(this.alice.userId().toString(), note.payloadOrEmpty().get("byUser")),
			() -> assertTrue(this.carol.sawType(MessageType.NOTE))
		);
	}
	
	@Test
	void note_withAnInvalidCellOrDigit_isIgnored() {
		this.start(false, this.alice, this.bob);
		this.bob.clear();
		
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", 9999, "digit", 1, "add", true));
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", 0, "digit", 9999, "add", true));
		
		assertFalse(this.bob.sawType(MessageType.NOTE));
	}
	
	@Test
	void note_onAGivenCell_isIgnored() {
		// There is nothing to annotate under a digit that was never in question.
		this.start(false, this.alice, this.bob);
		int given = MatchFixture.givens(this.puzzle).getFirst();
		this.bob.clear();
		
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", given, "digit", 1, "add", true));
		
		assertFalse(this.bob.sawType(MessageType.NOTE));
	}
	
	/**
	 * Multiplayer-game item 1: hints are a property of the match, so a joiner learns them from the match.
	 * <p>
	 * The Android client had this as an in-game switch, which two players on one shared board could set
	 * differently. It sits beside {@code livesEnabled} in the snapshot now, decided once at creation.
	 * </p>
	 */
	@Test
	void matchState_carriesWhetherHintsAreEnabled() {
		Match match = MatchFixture.match(MatchMode.COOP, false, false, 0, this.puzzle);
		this.coop = new CoopMatch(match, this.puzzle, MatchFixture.matchConfig(), this.callbacks);
		connect(this.coop, this.alice);
		
		assertEquals(false, this.alice.lastOf(MessageType.MATCH_STATE).payloadOrEmpty().get("hintsEnabled"));
	}
	
	@Test
	void matchState_hintsDefaultToEnabled() {
		this.start(false, this.alice, this.bob);
		
		assertEquals(true, this.alice.lastOf(MessageType.MATCH_STATE).payloadOrEmpty().get("hintsEnabled"));
	}
	
	@Test
	void matchState_carriesTheAccumulatedNotes() {
		// Kept server-side rather than only echoed: a note that existed solely as a broadcast would be
		// invisible to anyone who joined or reconnected afterwards, which is the case MATCH_STATE exists for.
		this.start(false, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", cell, "digit", 1, "add", true));
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", cell, "digit", 3, "add", true));
		this.carol.clear();
		
		connect(this.coop, this.carol);
		
		Object notes = this.carol.lastOf(MessageType.MATCH_STATE).payloadOrEmpty().get("notes");
		assertEquals(Map.of(Integer.toString(cell), (1 << 1) | (1 << 3)), notes);
	}
	
	@Test
	void note_removedAgain_dropsTheCellFromTheSnapshot() {
		this.start(false, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", cell, "digit", 1, "add", true));
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", cell, "digit", 1, "add", false));
		this.carol.clear();
		
		connect(this.coop, this.carol);
		
		assertEquals(Map.of(), this.carol.lastOf(MessageType.MATCH_STATE).payloadOrEmpty().get("notes"));
	}
	
	@Test
	void place_aCorrectDigit_retiresThatCellsNotes() {
		// The notes on a solved cell annotate nothing, and a reconnecting player must not be handed
		// candidates for a cell that already holds a digit.
		this.start(false, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", cell, "digit", 1, "add", true));
		place(this.coop, this.alice, cell, this.puzzle.solutionAt(cell));
		this.carol.clear();
		
		connect(this.coop, this.carol);
		
		assertEquals(Map.of(), this.carol.lastOf(MessageType.MATCH_STATE).payloadOrEmpty().get("notes"));
	}
	
	@Test
	void place_aCorrectDigit_clearsThatDigitFromThePeersNotes() {
		// Multiplayer item 1 of 2.2.0: spec 5.6's auto-clear-peers, which co-op never had. Single-player does
		// it in the client's board editor, which no multiplayer mode goes through - and the co-op notes are
		// the group's and live here, so this is the only place that can rub the placed digit out of them.
		// It goes out as an ordinary NOTE removal, so a client applies it through the path it already has.
		this.start(false, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		int peer = this.peerHole(cell);
		int digit = this.puzzle.solutionAt(cell);
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", peer, "digit", digit, "add", true));
		this.bob.clear();
		this.carol.clear();
		
		place(this.coop, this.alice, cell, digit);
		
		MessageEnvelope note = this.bob.lastOf(MessageType.NOTE);
		connect(this.coop, this.carol);
		assertAll(
			() -> assertNotNull(note),
			() -> assertEquals(peer, note.payloadOrEmpty().get("cell")),
			() -> assertEquals(digit, note.payloadOrEmpty().get("digit")),
			() -> assertEquals(false, note.payloadOrEmpty().get("add")),
			// And it is gone from the snapshot too, so a reconnecting player is not handed it back.
			() -> assertEquals(Map.of(), this.carol.lastOf(MessageType.MATCH_STATE).payloadOrEmpty().get("notes"))
		);
	}
	
	@Test
	void place_aCorrectDigit_leavesANoteOnAnotherDigitAlone() {
		// Only the placed digit becomes impossible. Everything else the peer cell could still hold is the
		// group's own work and has to survive the entry.
		this.start(false, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		int peer = this.peerHole(cell);
		int digit = this.puzzle.solutionAt(cell);
		int other = digit == 1 ? 2 : 1;
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", peer, "digit", other, "add", true));
		this.carol.clear();
		
		place(this.coop, this.alice, cell, digit);
		
		connect(this.coop, this.carol);
		assertEquals(
			Map.of(Integer.toString(peer), 1 << other),
			this.carol.lastOf(MessageType.MATCH_STATE).payloadOrEmpty().get("notes")
		);
	}
	
	@Test
	void place_aCorrectDigit_leavesANoteOutsideItsUnitsAlone() {
		// A cell that shares no row, column or region with the entry learns nothing from it, so its notes
		// are none of auto-clear-peers' business.
		this.start(false, this.alice, this.bob);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		int elsewhere = this.nonPeerHole(cell);
		int digit = this.puzzle.solutionAt(cell);
		send(this.coop, this.alice, MessageType.NOTE, Map.of("cell", elsewhere, "digit", digit, "add", true));
		this.carol.clear();
		
		place(this.coop, this.alice, cell, digit);
		
		connect(this.coop, this.carol);
		assertEquals(
			Map.of(Integer.toString(elsewhere), 1 << digit),
			this.carol.lastOf(MessageType.MATCH_STATE).payloadOrEmpty().get("notes")
		);
	}
	
	/** @return another empty cell sharing the given cell's row or column - one auto-clear-peers reaches */
	private int peerHole(int cell) {
		int n = MatchFixture.SIZE.n();
		for (int hole : MatchFixture.holes(this.puzzle)) {
			if (hole != cell && (hole / n == cell / n || hole % n == cell % n)) {
				return hole;
			}
		}
		throw new IllegalStateException("the fixture puzzle has no second empty cell in the units of " + cell);
	}
	
	/** @return an empty cell sharing no row, column or region with the given one */
	private int nonPeerHole(int cell) {
		int n = MatchFixture.SIZE.n();
		for (int hole : MatchFixture.holes(this.puzzle)) {
			boolean sameRegion = this.puzzle.puzzle().partition().regionOf(hole) == this.puzzle.puzzle().partition().regionOf(cell);
			if (hole != cell && hole / n != cell / n && hole % n != cell % n && !sameRegion) {
				return hole;
			}
		}
		throw new IllegalStateException("the fixture puzzle has no empty cell outside the units of " + cell);
	}
	
	@Test
	void onMessage_aDuelOnlyType_isRejected() {
		this.start(false, this.alice, this.bob);
		this.alice.clear();
		
		send(this.coop, this.alice, MessageType.BACKGROUNDED, Map.of());
		
		assertEquals("UNSUPPORTED", this.alice.lastOf(MessageType.ERROR).payloadOrEmpty().get("error"));
	}
}
