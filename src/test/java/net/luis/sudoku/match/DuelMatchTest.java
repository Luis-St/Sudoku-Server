package net.luis.sudoku.match;

import net.luis.sudoku.config.DuelConfig;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.match.support.FakeConnection;
import net.luis.sudoku.match.support.MatchFixture;
import net.luis.sudoku.match.support.RecordingCallbacks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.luis.sudoku.match.support.MatchFixture.connect;
import static net.luis.sudoku.match.support.MatchFixture.place;
import static net.luis.sudoku.match.support.MatchFixture.ready;
import static net.luis.sudoku.match.support.MatchFixture.send;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link DuelMatch}, covering server-spec 11.2.
 */
class DuelMatchTest {

	private GeneratedPuzzle puzzle;
	private RecordingCallbacks callbacks;
	private DuelMatch duel;
	private FakeConnection alice;
	private FakeConnection bob;

	@BeforeEach
	void createMatch() {
		this.puzzle = MatchFixture.puzzle();
		this.callbacks = new RecordingCallbacks();
		this.alice = FakeConnection.of("Alice");
		this.bob = FakeConnection.of("Bob");
	}

	private void start(@org.jspecify.annotations.NonNull DuelConfig config) {
		Match match = MatchFixture.match(MatchMode.DUEL, false, 10, this.puzzle);
		this.duel = new DuelMatch(match, this.puzzle, MatchFixture.matchConfig(), config, this.callbacks);
		connect(this.duel, this.alice);
		connect(this.duel, this.bob);
		ready(this.duel, this.alice, this.bob);
	}

	private void start() {
		this.start(MatchFixture.duelConfig());
	}

	/**
	 * @return whichever connection currently holds the board
	 */
	private FakeConnection controllerConnection() {
		UUID controller = this.duel.controller();
		return this.alice.userId().equals(controller) ? this.alice : this.bob;
	}

	private FakeConnection idleConnection() {
		return this.controllerConnection() == this.alice ? this.bob : this.alice;
	}

	@AfterEach
	void shutdown() {
		if (this.duel != null) {
			this.duel.shutdown();
		}
	}

	@Test
	void start_givesTheBoardToOneParticipantAndAnnouncesIt() {
		this.start();

		assertAll(
			() -> assertEquals(MatchState.RUNNING, this.duel.state()),
			() -> assertNotNull(this.duel.controller()),
			() -> assertTrue(this.alice.sawType(MessageType.CONTROL_CHANGED)),
			() -> assertTrue(this.bob.sawType(MessageType.CONTROL_CHANGED))
		);
	}

	@Test
	void start_bothBanksBeginAtTheConfiguredInitialBank() {
		this.start();

		long expected = MatchFixture.duelConfig().initialBank() * 1000L;
		assertAll(
			() -> assertTrue(this.duel.bankOf(this.alice.userId()) <= expected),
			() -> assertTrue(this.duel.bankOf(this.alice.userId()) > expected - 5_000),
			() -> assertTrue(this.duel.bankOf(this.bob.userId()) <= expected)
		);
	}

	@Test
	void place_fromTheNonControllingPlayer_isRejectedOutright() {
		this.start();
		FakeConnection idle = this.idleConnection();
		idle.clear();

		place(this.duel, idle, MatchFixture.holes(this.puzzle).getFirst(), 1);

		MessageEnvelope error = idle.lastOf(MessageType.ERROR);
		assertAll(
			() -> assertNotNull(error),
			() -> assertEquals("NOT_YOUR_TURN", error.payloadOrEmpty().get("error"))
		);
	}

	@Test
	void place_aCorrectDigit_writesTheSharedBoardAndCreditsTheBank() {
		this.start();
		FakeConnection controller = this.controllerConnection();
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		long before = this.duel.bankOf(controller.userId());
		this.alice.clear();
		this.bob.clear();

		place(this.duel, controller, cell, this.puzzle.solutionAt(cell));

		assertAll(
			() -> assertTrue(this.alice.sawType(MessageType.BOARD_UPDATE), "the pen layer is shared"),
			() -> assertTrue(this.bob.sawType(MessageType.BOARD_UPDATE)),
			() -> assertTrue(this.duel.bankOf(controller.userId()) > before - 2_000, "the bank was credited")
		);
	}

	@Test
	void place_anIncorrectDigit_isNotWrittenToTheSharedBoard() {
		// Spec 11.2: wrong entries never reach the shared board; the red-then-removed animation is
		// purely client-side on the rejection.
		this.start();
		FakeConnection controller = this.controllerConnection();
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		this.alice.clear();
		this.bob.clear();

		place(this.duel, controller, cell, MatchFixture.wrongDigitFor(this.puzzle, cell));

		MessageEnvelope entry = controller.lastOf(MessageType.ENTRY_RESULT);
		assertAll(
			() -> assertNotNull(entry),
			() -> assertEquals(false, entry.payloadOrEmpty().get("correct")),
			() -> assertFalse(this.alice.sawType(MessageType.BOARD_UPDATE)),
			() -> assertFalse(this.bob.sawType(MessageType.BOARD_UPDATE))
		);
	}

	@Test
	void place_anIncorrectDigit_debitsTheBank() {
		this.start();
		FakeConnection controller = this.controllerConnection();
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		long before = this.duel.bankOf(controller.userId());

		place(this.duel, controller, cell, MatchFixture.wrongDigitFor(this.puzzle, cell));

		long penalty = MatchFixture.duelConfig().lossPerIncorrect() * 1000L;
		assertTrue(this.duel.bankOf(controller.userId()) <= before - penalty + 2_000,
			"a wrong entry should cost roughly lossPerIncorrect");
	}

	@Test
	void place_theFinalCorrectCell_winsTheMatch() {
		this.start();
		// Control can change mid-solve, so always place as whoever currently holds the board.
		for (int cell : MatchFixture.holes(this.puzzle)) {
			place(this.duel, this.controllerConnection(), cell, this.puzzle.solutionAt(cell));
			if (this.callbacks.ended() != null) {
				break;
			}
		}

		RecordingCallbacks.Ended ended = this.callbacks.ended();
		assertAll(
			() -> assertNotNull(ended),
			() -> assertEquals(EndReason.COMPLETED, ended.reason()),
			() -> assertNotNull(ended.winnerId())
		);
	}

	@Test
	void backgrounded_forfeitsImmediatelyToTheOpponent() {
		// Spec 11.2: pausing instead would let a player under time pressure freeze their bank.
		this.start();

		send(this.duel, this.alice, MessageType.BACKGROUNDED, Map.of());

		RecordingCallbacks.Ended ended = this.callbacks.ended();
		assertAll(
			() -> assertNotNull(ended),
			() -> assertEquals(EndReason.FORFEIT_BACKGROUNDED, ended.reason()),
			() -> assertEquals(this.bob.userId(), ended.winnerId()),
			() -> assertEquals(MatchState.ENDED, ended.state())
		);
	}

	@Test
	void socketClose_isTreatedAsANetworkFailureRatherThanAForfeit() {
		// The distinction the spec draws explicitly: a tunnel must never cost a duel.
		this.start();

		this.duel.submit(() -> this.duel.onDisconnect(this.alice.userId(), false));
		MatchFixture.drain(this.duel);

		assertNull(this.callbacks.ended(), "the grace window applies instead of an immediate loss");
	}

	@Test
	void onDisconnect_stopsTheClocksUntilTheParticipantReturns() {
		this.start();
		this.duel.submit(() -> this.duel.onDisconnect(this.alice.userId(), false));
		MatchFixture.drain(this.duel);
		long parked = this.duel.bankOf(this.duel.controller());

		try {
			Thread.sleep(600);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		MatchFixture.drain(this.duel);

		assertEquals(parked, this.duel.bankOf(this.duel.controller()),
			"duel clocks stop while a participant is inside the grace window");
	}

	@Test
	void tick_handsOverOnceTheBankIsSpentAndTheMinimumTurnHasElapsed() throws Exception {
		// A one-second initial bank and a one-second minimum turn make this fast without changing logic.
		this.start(MatchFixture.fastDuelConfig());
		UUID first = this.duel.controller();

		UUID current = first;
		for (int attempt = 0; attempt < 60 && current.equals(first); attempt++) {
			Thread.sleep(100);
			MatchFixture.drain(this.duel);
			current = this.duel.controller();
		}

		assertAll(
			() -> assertNotEquals(first, this.duel.controller(), "the board should have changed hands"),
			() -> assertTrue(this.duel.handoverNo() >= 1)
		);
	}

	@Test
	void tick_theIdlePlayerRegeneratesSoTheyCanActuallyPlay() throws Exception {
		// Without regeneration a player would receive control with an empty bank and hand straight back,
		// deadlocking the match (spec 11.2).
		this.start(MatchFixture.fastDuelConfig());
		UUID idle = this.alice.userId().equals(this.duel.controller()) ? this.bob.userId() : this.alice.userId();
		long before = this.duel.bankOf(idle);

		Thread.sleep(400);
		MatchFixture.drain(this.duel);

		assertTrue(this.duel.bankOf(idle) > before, "the idle bank should have grown");
	}

	@Test
	void tick_reachingTheHandoverCap_endsOnCorrectCells() throws Exception {
		this.start(MatchFixture.stalemateDuelConfig());
		// The controller banks one correct cell, so the tie-break has something to decide on.
		int cell = MatchFixture.holes(this.puzzle).getFirst();
		FakeConnection scorer = this.controllerConnection();
		place(this.duel, scorer, cell, this.puzzle.solutionAt(cell));

		RecordingCallbacks.Ended ended = null;
		for (int attempt = 0; attempt < 100 && ended == null; attempt++) {
			Thread.sleep(100);
			MatchFixture.drain(this.duel);
			ended = this.callbacks.ended();
		}

		assertAll(
			() -> assertNotNull(this.callbacks.ended(), "the handover cap should have ended the match"),
			() -> assertEquals(EndReason.STALEMATE, this.callbacks.ended().reason()),
			() -> assertEquals(scorer.userId(), this.callbacks.ended().winnerId(), "most correct cells wins")
		);
	}

	@Test
	void resign_handsTheWinToTheOpponent() {
		this.start();

		send(this.duel, this.bob, MessageType.RESIGN, Map.of());

		RecordingCallbacks.Ended ended = this.callbacks.ended();
		assertAll(
			() -> assertNotNull(ended),
			() -> assertEquals(this.alice.userId(), ended.winnerId()),
			() -> assertEquals(EndReason.RESIGNED, ended.reason())
		);
	}

	@Test
	void matchState_carriesBanksControlAndTheSharedBoard() {
		this.start();
		this.alice.clear();

		connect(this.duel, this.alice);

		MessageEnvelope state = this.alice.lastOf(MessageType.MATCH_STATE);
		assertAll(
			() -> assertNotNull(state),
			() -> assertTrue(state.payloadOrEmpty().containsKey("banks")),
			() -> assertTrue(state.payloadOrEmpty().containsKey("controller")),
			() -> assertTrue(state.payloadOrEmpty().containsKey("board")),
			() -> assertEquals(10, state.payloadOrEmpty().get("stake"))
		);
	}

	@Test
	void note_isNeverBroadcast() {
		// Spec 10.5: the pen layer is shared, the pencil layer is not.
		this.start();
		this.bob.clear();

		send(this.duel, this.alice, MessageType.NOTE, Map.of("cell", 0, "digit", 1, "add", true));

		assertTrue(this.bob.received().isEmpty(), "a note must not reach the opponent");
	}

	@Test
	void place_anAlreadyFilledCell_isIgnored() {
		this.start();
		FakeConnection controller = this.controllerConnection();
		List<Integer> holes = MatchFixture.holes(this.puzzle);
		int cell = holes.getFirst();
		place(this.duel, controller, cell, this.puzzle.solutionAt(cell));
		FakeConnection nowController = this.controllerConnection();
		nowController.clear();

		place(this.duel, nowController, cell, this.puzzle.solutionAt(cell));

		assertFalse(nowController.sawType(MessageType.ENTRY_RESULT));
	}
}
