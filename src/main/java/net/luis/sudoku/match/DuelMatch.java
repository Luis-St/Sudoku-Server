package net.luis.sudoku.match;

import net.luis.sudoku.config.DuelConfig;
import net.luis.sudoku.config.MatchConfig;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.generation.GeneratedPuzzle;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Duel mode (server-spec 11.2).
 * <p>
 * The server owns both clocks and the shared board. Clients render an interpolated countdown, but
 * handover is decided solely here and pushed - a client never acts on its own clock.
 */
public final class DuelMatch extends LiveMatch {
	
	private static final Logger log = LoggerFactory.getLogger(DuelMatch.class);
	
	/** Spec 11.2: "roughly every 250 ms". */
	private static final long TICK_MS = 250;
	
	/** Banks are broadcast at about 1Hz; clients interpolate between updates (spec 11.2). */
	private static final long BANK_BROADCAST_MS = 1000;
	
	private final DuelConfig duel;
	private final Map<UUID, Player> players = new LinkedHashMap<>();
	private final BitSet filled;
	private final int holes;
	
	private @Nullable UUID controller;
	private long turnStartedAtMs;
	private int handoverNo;
	private long lastTickMs;
	private long lastBankBroadcastMs;
	private @Nullable ScheduledFuture<?> tickLoop;
	
	public DuelMatch(@NonNull Match match, @NonNull GeneratedPuzzle puzzle, @NonNull MatchConfig config,
	                 @NonNull DuelConfig duel, @NonNull MatchCallbacks callbacks) {
		super(match, puzzle, config, callbacks);
		this.duel = duel;
		this.filled = new BitSet(puzzle.puzzle().size().cellCount());
		
		int empty = 0;
		for (int index = 0; index < puzzle.puzzle().size().cellCount(); index++) {
			if (!puzzle.puzzle().cell(index).isGiven()) {
				empty++;
			}
		}
		this.holes = empty;
	}
	
	@Override
	public void onMessage(@NonNull UUID userId, @NonNull MessageType type, @NonNull Map<String, Object> payload) {
		switch (type) {
			case READY -> this.onReady(userId);
			case PLACE -> this.onPlace(userId, payload);
			case RESIGN -> this.onResign(userId);
			case BACKGROUNDED -> this.onBackgrounded(userId);
			// Notes are private and never broadcast, even though the pen layer is shared (spec 10.5).
			case NOTE, HELLO -> {}
			default -> this.sendTo(userId, MessageEnvelope.of(MessageType.ERROR, Map.of("error", "UNSUPPORTED", "message", type + " is not valid in a duel")));
		}
	}
	
	private void onReady(@NonNull UUID userId) {
		ParticipantState participant = this.participant(userId);
		if (participant == null || this.state() == MatchState.RUNNING) {
			return;
		}
		participant.ready = true;
		if (this.allReady()) {
			this.start();
		}
	}
	
	@Override
	protected void onStarted() {
		long now = System.currentTimeMillis();
		for (UUID userId : this.participants().keySet()) {
			this.players.put(userId, new Player(this.duel.initialBank() * 1000L));
		}
		// First to join takes the board first; iteration order is insertion order.
		this.controller = this.players.keySet().iterator().next();
		this.turnStartedAtMs = now;
		this.lastTickMs = now;
		this.lastBankBroadcastMs = now;
		
		this.broadcastControl();
		this.tickLoop = this.scheduleRepeating(this::tick, TICK_MS, TimeUnit.MILLISECONDS);
	}
	
	/**
	 * The tick loop (spec 11.2). Runs on the match queue, so it never races an entry.
	 */
	private void tick() {
		if (this.hasEnded() || this.state() != MatchState.RUNNING || this.controller == null) {
			return;
		}
		long now = System.currentTimeMillis();
		long elapsed = now - this.lastTickMs;
		this.lastTickMs = now;
		if (elapsed <= 0) {
			return;
		}
		
		Player active = this.players.get(this.controller);
		if (active == null) {
			return;
		}
		active.bankMs -= elapsed;
		
		// The idle player regenerates. Without this they would receive control with an empty bank -
		// emptying it is what ended their last turn - and hand straight back, deadlocking the match.
		long maxBankMs = this.duel.maxBank() * 1000L;
		for (Map.Entry<UUID, Player> entry : this.players.entrySet()) {
			if (!entry.getKey().equals(this.controller)) {
				entry.getValue().bankMs = Math.min(maxBankMs, entry.getValue().bankMs + (long) (elapsed * this.duel.regenRatio()));
			}
		}
		
		boolean turnLongEnough = now - this.turnStartedAtMs >= this.duel.minTurn() * 1000L;
		if (active.bankMs <= 0 && turnLongEnough) {
			this.handover(now);
		}
		
		if (now - this.lastBankBroadcastMs >= BANK_BROADCAST_MS) {
			this.lastBankBroadcastMs = now;
			this.broadcastBanks();
		}
	}
	
	private void handover(long now) {
		UUID next = this.players.keySet().stream()
			.filter(id -> !id.equals(this.controller))
			.findFirst()
			.orElse(null);
		if (next == null) {
			return;
		}
		
		this.controller = next;
		this.turnStartedAtMs = now;
		this.handoverNo++;
		
		if (this.handoverNo >= this.duel.maxHandovers()) {
			this.endOnStalemate();
			return;
		}
		this.broadcastControl();
	}
	
	/**
	 * The stalemate cap: most correct cells wins, ties broken by fewer errors (spec 11.2).
	 */
	private void endOnStalemate() {
		UUID best = null;
		int bestCorrect = -1;
		int bestErrors = Integer.MAX_VALUE;
		boolean tied = false;
		
		for (Map.Entry<UUID, Player> entry : this.players.entrySet()) {
			Player player = entry.getValue();
			if (player.correct > bestCorrect
				|| (player.correct == bestCorrect && player.errors < bestErrors)) {
				best = entry.getKey();
				bestCorrect = player.correct;
				bestErrors = player.errors;
				tied = false;
			} else if (player.correct == bestCorrect && player.errors == bestErrors) {
				tied = true;
			}
		}
		
		this.endMatch(tied ? null : best, EndReason.STALEMATE);
	}
	
	private void onPlace(@NonNull UUID userId, @NonNull Map<String, Object> payload) {
		if (this.state() != MatchState.RUNNING || this.hasEnded()) {
			return;
		}
		if (!userId.equals(this.controller)) {
			// Spec 11.2: an entry from the non-controlling player is rejected outright.
			this.sendTo(userId, MessageEnvelope.of(MessageType.ERROR, Map.of("error", "NOT_YOUR_TURN", "message", "You do not have the board")));
			return;
		}
		
		Integer cell = MatchPayloads.cell(payload, this.size());
		Integer digit = MatchPayloads.digit(payload, this.size());
		if (cell == null || digit == null) {
			this.sendTo(userId, MessageEnvelope.of(MessageType.ERROR, Map.of("error", "BAD_REQUEST", "message", "cell and digit must be valid for this grid")));
			return;
		}
		if (this.puzzle.puzzle().cell(cell).isGiven() || this.filled.get(cell)) {
			return;
		}
		
		Player player = this.players.get(userId);
		boolean correct = this.puzzle.solutionAt(cell) == digit;
		long maxBankMs = this.duel.maxBank() * 1000L;
		
		if (correct) {
			this.filled.set(cell);
			player.correct++;
			player.bankMs = Math.min(maxBankMs, player.bankMs + this.duel.gainPerCorrect() * 1000L);
		} else {
			player.errors++;
			// Clamped to maxBank per spec; the floor is left open because a negative bank is exactly
			// what triggers the handover on the next tick.
			player.bankMs = Math.min(maxBankMs, player.bankMs - this.duel.lossPerIncorrect() * 1000L);
		}
		
		Map<String, Object> result = new HashMap<>();
		result.put("cell", cell);
		result.put("digit", digit);
		result.put("correct", correct);
		result.put("livesLeft", null);
		this.sendTo(userId, new MessageEnvelope(MessageType.ENTRY_RESULT.name(), 0, System.currentTimeMillis(), result));
		
		if (correct) {
			// Only correct entries reach the shared board. The red-then-removed animation for a wrong
			// entry is purely client-side on the rejection (spec 11.2).
			this.broadcast(MessageEnvelope.of(MessageType.BOARD_UPDATE, Map.of(
				"cell", cell,
				"digit", digit,
				"byUser", userId.toString()
			)));
		}
		this.broadcastBanks();
		
		if (this.filled.cardinality() == this.holes) {
			this.endMatch(userId, EndReason.COMPLETED);
		}
	}
	
	/**
	 * Backgrounding forfeits immediately (spec 11.2).
	 * <p>
	 * Deliberately distinct from a socket simply closing: pausing on background would let a player under
	 * time pressure freeze their bank by leaving the app. A network drop still gets the ordinary grace
	 * window, so a tunnel never costs a duel.
	 */
	private void onBackgrounded(@NonNull UUID userId) {
		if (this.state() != MatchState.RUNNING || this.hasEnded()) {
			return;
		}
		UUID opponent = this.opponentOf(userId);
		log.info("Match {}: user {} backgrounded, forfeiting to {}", this.id(), userId, opponent);
		this.endMatch(opponent, EndReason.FORFEIT_BACKGROUNDED);
	}
	
	private void onResign(@NonNull UUID userId) {
		this.endMatch(this.opponentOf(userId), EndReason.RESIGNED);
	}
	
	private @Nullable UUID opponentOf(@NonNull UUID userId) {
		return this.participants().keySet().stream().filter(id -> !id.equals(userId)).findFirst().orElse(null);
	}
	
	private void broadcastControl() {
		this.broadcast(MessageEnvelope.of(MessageType.CONTROL_CHANGED, Map.of(
			"userId", String.valueOf(this.controller),
			"handoverNo", this.handoverNo
		)));
	}
	
	private void broadcastBanks() {
		this.players.forEach((userId, player) -> this.broadcast(MessageEnvelope.of(MessageType.BANK_UPDATE, Map.of(
			"userId", userId.toString(),
			"remainingMs", Math.max(0, player.bankMs)
		))));
	}
	
	@Override
	protected @NonNull MessageEnvelope matchStateMessage(@NonNull UUID forUser) {
		Map<String, Object> banks = new LinkedHashMap<>();
		this.players.forEach((userId, player) -> banks.put(userId.toString(), Math.max(0, player.bankMs)));
		
		Map<String, Object> board = new LinkedHashMap<>();
		this.filled.stream().forEach(cell -> board.put(Integer.toString(cell), this.puzzle.solutionAt(cell)));
		
		Map<String, Object> payload = new HashMap<>();
		payload.put("matchId", this.id().toString());
		payload.put("mode", this.mode().name());
		payload.put("state", this.state().name());
		payload.put("puzzleKey", MatchPayloads.key(this.match.key()));
		// The shared pen layer holds only correct entries, so sending it leaks nothing a participant
		// has not already earned.
		payload.put("board", board);
		payload.put("banks", banks);
		payload.put("controller", this.controller == null ? null : this.controller.toString());
		payload.put("handoverNo", this.handoverNo);
		payload.put("stake", this.match.stake());
		payload.put("participants", MatchPayloads.participants(this.participants().values()));
		
		return new MessageEnvelope(MessageType.MATCH_STATE.name(), 0, System.currentTimeMillis(), payload);
	}
	
	@Override
	protected void onParticipantDisconnected(@NonNull ParticipantState participant) {
		// Duel clocks stop while a participant is inside the grace window (spec 10.4).
		if (this.tickLoop != null) {
			this.tickLoop.cancel(false);
			this.tickLoop = null;
		}
	}
	
	@Override
	protected void onParticipantConnected(@NonNull ParticipantState participant) {
		if (this.state() == MatchState.RUNNING && this.tickLoop == null && !this.hasEnded()) {
			// Resume where the clocks stopped rather than charging the returning player for the outage.
			this.lastTickMs = System.currentTimeMillis();
			this.tickLoop = this.scheduleRepeating(this::tick, TICK_MS, TimeUnit.MILLISECONDS);
		}
	}
	
	@Override
	public void shutdown() {
		if (this.tickLoop != null) {
			this.tickLoop.cancel(false);
		}
		super.shutdown();
	}
	
	/**
	 * @return the current controller, for tests
	 */
	@Nullable UUID controller() {
		return this.controller;
	}
	
	int handoverNo() {
		return this.handoverNo;
	}
	
	long bankOf(@NonNull UUID userId) {
		Player player = this.players.get(userId);
		return player == null ? 0 : player.bankMs;
	}
	
	/**
	 * One duellist's clock and score. Milliseconds throughout, because the tick is sub-second.
	 */
	private static final class Player {
		
		private long bankMs;
		private int correct;
		private int errors;
		
		private Player(long bankMs) {
			this.bankMs = bankMs;
		}
	}
}
