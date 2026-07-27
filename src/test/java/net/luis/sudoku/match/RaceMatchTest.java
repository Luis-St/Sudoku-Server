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
 * Test class for {@link RaceMatch}, covering server-spec 11.1.
 */
class RaceMatchTest {
	
	private GeneratedPuzzle puzzle;
	private RecordingCallbacks callbacks;
	private RaceMatch race;
	private FakeConnection alice;
	private FakeConnection bob;
	
	@BeforeEach
	void createMatch() {
		this.puzzle = MatchFixture.puzzle();
		this.callbacks = new RecordingCallbacks();
		this.alice = FakeConnection.of("Alice");
		this.bob = FakeConnection.of("Bob");
	}
	
	private void start(boolean livesEnabled) {
		Match match = MatchFixture.match(MatchMode.RACE, livesEnabled, 0, this.puzzle);
		this.race = new RaceMatch(match, this.puzzle, MatchFixture.matchConfig(), this.callbacks);
		connect(this.race, this.alice);
		connect(this.race, this.bob);
		ready(this.race, this.alice, this.bob);
	}
	
	@AfterEach
	void shutdown() {
		if (this.race != null) {
			this.race.shutdown();
		}
	}
	
	@Test
	void onConnect_sendsAFullSnapshot() {
		Match match = MatchFixture.match(MatchMode.RACE, false, 0, this.puzzle);
		this.race = new RaceMatch(match, this.puzzle, MatchFixture.matchConfig(), this.callbacks);
		
		connect(this.race, this.alice);
		
		// MATCH_STATE on every connect is what makes the protocol resynchronising by construction.
		assertTrue(this.alice.sawType(MessageType.MATCH_STATE));
	}
	
	@Test
	void ready_fromBothParticipants_startsTheMatch() {
		this.start(false);
		
		assertAll(
			() -> assertEquals(MatchState.RUNNING, this.race.state()),
			() -> assertEquals(1, this.callbacks.starts())
		);
	}
	
	@Test
	void ready_fromOnlyOneParticipant_doesNotStart() {
		Match match = MatchFixture.match(MatchMode.RACE, false, 0, this.puzzle);
		this.race = new RaceMatch(match, this.puzzle, MatchFixture.matchConfig(), this.callbacks);
		connect(this.race, this.alice);
		connect(this.race, this.bob);
		
		ready(this.race, this.alice);
		
		assertNotEquals(MatchState.RUNNING, this.race.state());
	}
	
	@Test
	void place_aCorrectDigit_isReportedCorrectAndAdvancesProgress() {
		this.start(false);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		this.alice.clear();
		this.bob.clear();
		
		place(this.race, this.alice, cell, this.puzzle.solutionAt(cell));
		
		MessageEnvelope entry = this.alice.lastOf(MessageType.ENTRY_RESULT);
		MessageEnvelope progress = this.bob.lastOf(MessageType.PROGRESS);
		assertAll(
			() -> assertNotNull(entry),
			() -> assertEquals(true, entry.payloadOrEmpty().get("correct")),
			() -> assertNotNull(progress, "the opponent sees progress"),
			() -> assertEquals(this.alice.userId().toString(), progress.payloadOrEmpty().get("userId"))
		);
	}
	
	@Test
	void place_neverLeaksCellContentToTheOpponent() {
		// Spec 11.1: broadcast only a percentage, never cell content.
		this.start(false);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		this.bob.clear();
		
		place(this.race, this.alice, cell, this.puzzle.solutionAt(cell));
		
		assertAll(
			() -> assertFalse(this.bob.sawType(MessageType.BOARD_UPDATE), "race must not broadcast the board"),
			() -> assertFalse(this.bob.sawType(MessageType.ENTRY_RESULT), "entry results are private to the sender"),
			() -> assertTrue(this.bob.receivedOf(MessageType.PROGRESS).stream()
				.noneMatch(message -> message.payloadOrEmpty().containsKey("cell")
					|| message.payloadOrEmpty().containsKey("digit")))
		);
	}
	
	@Test
	void place_anIncorrectDigit_isReportedAndCostsALifeWhenEnabled() {
		this.start(true);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		this.alice.clear();
		
		place(this.race, this.alice, cell, MatchFixture.wrongDigitFor(this.puzzle, cell));
		
		MessageEnvelope entry = this.alice.lastOf(MessageType.ENTRY_RESULT);
		assertAll(
			() -> assertNotNull(entry),
			() -> assertEquals(false, entry.payloadOrEmpty().get("correct")),
			() -> assertEquals(RaceMatch.LIVES - 1, entry.payloadOrEmpty().get("livesLeft"))
		);
	}
	
	@Test
	void place_anIncorrectDigitWithLivesDisabled_reportsNoLives() {
		this.start(false);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		this.alice.clear();
		
		place(this.race, this.alice, cell, MatchFixture.wrongDigitFor(this.puzzle, cell));
		
		MessageEnvelope entry = this.alice.lastOf(MessageType.ENTRY_RESULT);
		assertNull(entry.payloadOrEmpty().get("livesLeft"));
	}
	
	@Test
	void place_onAGivenCell_isIgnored() {
		this.start(true);
		int given = -1;
		for (int index = 0; index < this.puzzle.puzzle().size().cellCount(); index++) {
			if (this.puzzle.puzzle().cell(index).isGiven()) {
				given = index;
				break;
			}
		}
		this.alice.clear();
		
		place(this.race, this.alice, given, 1);
		
		// A no-op, not a mistake: it must not consume a life or emit a result.
		assertFalse(this.alice.sawType(MessageType.ENTRY_RESULT));
	}
	
	@Test
	void place_anOutOfRangeCell_isRejected() {
		this.start(false);
		this.alice.clear();
		
		place(this.race, this.alice, 9999, 1);
		
		MessageEnvelope error = this.alice.lastOf(MessageType.ERROR);
		assertAll(
			() -> assertNotNull(error),
			() -> assertEquals("BAD_REQUEST", error.payloadOrEmpty().get("error"))
		);
	}
	
	@Test
	void place_anOutOfRangeDigit_isRejected() {
		this.start(false);
		this.alice.clear();
		
		// The fixture grid is 4x4, so 9 is not a legal digit (spec 12: validate against the grid size).
		place(this.race, this.alice, MatchFixture.holes(this.puzzle).getFirst(), 9);
		
		assertNotNull(this.alice.lastOf(MessageType.ERROR));
	}
	
	@Test
	void place_completingTheGrid_winsTheMatch() {
		this.start(false);
		for (int cell : MatchFixture.holes(this.puzzle)) {
			place(this.race, this.alice, cell, this.puzzle.solutionAt(cell));
		}
		
		RecordingCallbacks.Ended ended = this.callbacks.ended();
		assertAll(
			() -> assertNotNull(ended),
			() -> assertEquals(this.alice.userId(), ended.winnerId()),
			() -> assertEquals(EndReason.COMPLETED, ended.reason()),
			() -> assertEquals(MatchState.ENDED, ended.state())
		);
	}
	
	@Test
	void place_exhaustingOneParticipantsLives_doesNotEndTheMatchWhileTheOtherPlaysOn() {
		this.start(true);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		int wrong = MatchFixture.wrongDigitFor(this.puzzle, cell);
		
		for (int attempt = 0; attempt < RaceMatch.LIVES; attempt++) {
			// Each wrong entry into an unfilled cell costs a life.
			place(this.race, this.alice, cell, wrong);
		}
		
		// The survivor still has to earn the win by finishing (spec 11.1).
		assertNull(this.callbacks.ended());
	}
	
	@Test
	void place_theSurvivorCompletingAfterTheOtherWasEliminated_wins() {
		this.start(true);
		List<Integer> holes = MatchFixture.holes(this.puzzle);
		int cell = holes.getFirst();
		int wrong = MatchFixture.wrongDigitFor(this.puzzle, cell);
		for (int attempt = 0; attempt < RaceMatch.LIVES; attempt++) {
			place(this.race, this.alice, cell, wrong);
		}
		
		for (int hole : holes) {
			place(this.race, this.bob, hole, this.puzzle.solutionAt(hole));
		}
		
		RecordingCallbacks.Ended ended = this.callbacks.ended();
		assertAll(
			() -> assertNotNull(ended),
			() -> assertEquals(this.bob.userId(), ended.winnerId()),
			() -> assertEquals(EndReason.COMPLETED, ended.reason())
		);
	}
	
	@Test
	void place_anEliminatedParticipant_canNoLongerPlay() {
		this.start(true);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		int wrong = MatchFixture.wrongDigitFor(this.puzzle, cell);
		for (int attempt = 0; attempt < RaceMatch.LIVES; attempt++) {
			place(this.race, this.alice, cell, wrong);
		}
		this.alice.clear();
		
		place(this.race, this.alice, cell, this.puzzle.solutionAt(cell));
		
		assertFalse(this.alice.sawType(MessageType.ENTRY_RESULT));
	}
	
	@Test
	void place_bothParticipantsExhaustingLives_endsWithNoWinner() {
		this.start(true);
		List<Integer> holes = MatchFixture.holes(this.puzzle);
		int cell = holes.getFirst();
		int wrong = MatchFixture.wrongDigitFor(this.puzzle, cell);
		
		// Interleave so neither is eliminated before the other has spent their lives.
		for (int attempt = 0; attempt < RaceMatch.LIVES; attempt++) {
			place(this.race, this.alice, cell, wrong);
			place(this.race, this.bob, cell, wrong);
		}
		
		RecordingCallbacks.Ended ended = this.callbacks.ended();
		assertAll(
			() -> assertNotNull(ended),
			() -> assertNull(ended.winnerId(), "if both fail there is no winner"),
			() -> assertEquals(EndReason.LIVES_EXHAUSTED, ended.reason())
		);
	}
	
	@Test
	void resign_handsTheWinToTheOpponent() {
		this.start(false);
		
		send(this.race, this.alice, MessageType.RESIGN, Map.of());
		
		RecordingCallbacks.Ended ended = this.callbacks.ended();
		assertAll(
			() -> assertNotNull(ended),
			() -> assertEquals(this.bob.userId(), ended.winnerId()),
			() -> assertEquals(EndReason.RESIGNED, ended.reason())
		);
	}
	
	@Test
	void onMessage_aDuelOnlyType_isRejected() {
		this.start(false);
		this.alice.clear();
		
		// BACKGROUNDED is duel-only; race applies the ordinary grace window instead (spec 11.2).
		send(this.race, this.alice, MessageType.BACKGROUNDED, Map.of());
		
		MessageEnvelope error = this.alice.lastOf(MessageType.ERROR);
		assertAll(
			() -> assertNotNull(error),
			() -> assertEquals("UNSUPPORTED", error.payloadOrEmpty().get("error"))
		);
	}
	
	@Test
	void onDisconnect_anExplicitQuit_endsTheMatchWithNoGrace() {
		this.start(false);
		
		this.race.submit(() -> this.race.onDisconnect(this.alice.userId(), true));
		MatchFixture.drain(this.race);
		
		RecordingCallbacks.Ended ended = this.callbacks.ended();
		assertAll(
			() -> assertNotNull(ended),
			() -> assertEquals(EndReason.RESIGNED, ended.reason())
		);
	}
	
	@Test
	void onDisconnect_exceedingTheReconnectCap_abandonsTheMatch() {
		this.start(false);
		int limit = MatchFixture.matchConfig().reconnectLimit();
		
		for (int attempt = 0; attempt <= limit; attempt++) {
			this.race.submit(() -> this.race.onDisconnect(this.alice.userId(), false));
			MatchFixture.drain(this.race);
			connect(this.race, this.alice);
		}
		
		RecordingCallbacks.Ended ended = this.callbacks.ended();
		assertAll(
			() -> assertNotNull(ended, "a flapping connection must not hold the other player hostage"),
			() -> assertEquals(EndReason.RECONNECT_LIMIT, ended.reason()),
			() -> assertEquals(MatchState.ABANDONED, ended.state())
		);
	}
	
	@Test
	void matchState_afterProgress_reportsOnlyTheOwnersFilledCells() {
		this.start(false);
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		place(this.race, this.alice, cell, this.puzzle.solutionAt(cell));
		this.bob.clear();
		
		connect(this.race, this.bob);
		
		MessageEnvelope state = this.bob.lastOf(MessageType.MATCH_STATE);
		@SuppressWarnings("unchecked")
		List<Integer> filled = (List<Integer>) state.payloadOrEmpty().get("filledCells");
		assertTrue(filled.isEmpty(), "Bob has solved nothing, so his snapshot shows nothing");
	}
}
