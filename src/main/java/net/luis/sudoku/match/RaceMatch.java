package net.luis.sudoku.match;

import net.luis.sudoku.config.MatchConfig;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.generation.GeneratedPuzzle;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * Race mode (server-spec 11.1).
 * <p>
 * Both participants get the same {@link net.luis.sudoku.key.PuzzleKey} and <strong>independent</strong>
 * boards. The server validates each entry against the known solution and tracks progress, but
 * broadcasts only a percentage - <strong>never cell content</strong>, which would let one player read
 * the other's answers.
 * <p>
 * The server does not keep a full board per player. Counting correctly-filled cells is all the rules
 * need, so a bitset per participant is enough.
 */
public final class RaceMatch extends LiveMatch {
	
	/** Matching the single-player rule (feature-spec 6): five mistakes and you are out. */
	public static final int LIVES = 5;
	
	private final Map<UUID, Board> boards = new LinkedHashMap<>();
	private final int holes;
	
	public RaceMatch(@NonNull Match match, @NonNull GeneratedPuzzle puzzle, @NonNull MatchConfig config,
	                 @NonNull MatchCallbacks callbacks) {
		super(match, puzzle, config, callbacks);
		
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
			// NOTE is private to the sender and never authoritative (spec 10.5); nothing to do server-side
			// in race, where boards are independent anyway.
			case NOTE, PRESENCE, HELLO -> {}
			default -> this.sendTo(userId, MessageEnvelope.of(MessageType.ERROR,
				Map.of("error", "UNSUPPORTED", "message", type + " is not valid in a race")));
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
		for (UUID userId : this.participants().keySet()) {
			this.boards.computeIfAbsent(userId, id -> new Board(this.size().cellCount()));
		}
	}
	
	private void onPlace(@NonNull UUID userId, @NonNull Map<String, Object> payload) {
		if (this.state() != MatchState.RUNNING || this.hasEnded()) {
			return;
		}
		Board board = this.boards.get(userId);
		if (board == null || board.eliminated) {
			return;
		}
		
		Integer cell = MatchPayloads.cell(payload, this.size());
		Integer digit = MatchPayloads.digit(payload, this.size());
		if (cell == null || digit == null) {
			this.sendTo(userId, MessageEnvelope.of(MessageType.ERROR,
				Map.of("error", "BAD_REQUEST", "message", "cell and digit must be valid for this grid")));
			return;
		}
		if (this.puzzle.puzzle().cell(cell).isGiven() || board.filled.get(cell)) {
			// Re-entering a given or an already-solved cell is a no-op, not a mistake.
			return;
		}
		
		boolean correct = this.puzzle.solutionAt(cell) == digit;
		if (correct) {
			board.filled.set(cell);
			board.correct++;
		} else if (this.match.livesEnabled()) {
			board.livesLeft--;
		}
		
		Map<String, Object> result = new HashMap<>();
		result.put("cell", cell);
		result.put("digit", digit);
		result.put("correct", correct);
		result.put("livesLeft", this.match.livesEnabled() ? board.livesLeft : null);
		this.sendTo(userId, new MessageEnvelope(MessageType.ENTRY_RESULT.name(), 0, System.currentTimeMillis(), result));
		
		// Progress carries a percentage only. Broadcasting the cell would leak the answer (spec 11.1).
		this.broadcast(MessageEnvelope.of(MessageType.PROGRESS, Map.of(
			"userId", userId.toString(),
			"filledPercent", this.holes == 0 ? 100 : board.correct * 100 / this.holes
		)));
		
		if (board.correct == this.holes) {
			this.endMatch(userId, EndReason.COMPLETED);
			return;
		}
		if (this.match.livesEnabled() && board.livesLeft <= 0) {
			board.eliminated = true;
			this.onEliminated(userId);
		}
	}
	
	/**
	 * A participant out of lives is out of the race (spec 11.1).
	 * <p>
	 * The match does <em>not</em> end here while anyone is still playing: the survivor still has to
	 * finish the grid to win, and may yet exhaust their own lives. That is what makes the spec's "if both
	 * fail, the match ends with no winner" reachable - ending on the first elimination would hand the
	 * survivor a win they had not earned and make that clause dead.
	 */
	private void onEliminated(@NonNull UUID eliminated) {
		this.broadcast(MessageEnvelope.of(MessageType.PROGRESS, Map.of(
			"userId", eliminated.toString(),
			"eliminated", true
		)));
		
		boolean anyoneLeft = this.boards.values().stream().anyMatch(board -> !board.eliminated);
		if (!anyoneLeft) {
			this.endMatch(null, EndReason.LIVES_EXHAUSTED);
		}
	}
	
	private void onResign(@NonNull UUID userId) {
		UUID opponent = this.participants().keySet().stream()
			.filter(id -> !id.equals(userId))
			.findFirst()
			.orElse(null);
		this.endMatch(opponent, EndReason.RESIGNED);
	}
	
	@Override
	protected @NonNull MessageEnvelope matchStateMessage(@NonNull UUID forUser) {
		Board own = this.boards.get(forUser);
		
		Map<String, Object> progress = new LinkedHashMap<>();
		this.boards.forEach((userId, board) ->
			progress.put(userId.toString(), this.holes == 0 ? 100 : board.correct * 100 / this.holes));
		
		Map<String, Object> payload = new HashMap<>();
		payload.put("matchId", this.id().toString());
		payload.put("mode", this.mode().name());
		payload.put("state", this.state().name());
		payload.put("puzzleKey", MatchPayloads.key(this.match.key()));
		payload.put("livesEnabled", this.match.livesEnabled());
		payload.put("livesLeft", own == null || !this.match.livesEnabled() ? null : own.livesLeft);
		// Only the cells this player has solved - never the opponent's.
		payload.put("filledCells", own == null ? List.of() : own.filledCells());
		payload.put("progress", progress);
		payload.put("participants", MatchPayloads.participants(this.participants().values()));
		
		return new MessageEnvelope(MessageType.MATCH_STATE.name(), 0, System.currentTimeMillis(), payload);
	}
	
	/**
	 * One participant's independent board. Only which cells are correctly filled matters, so the digits
	 * themselves are never stored server-side.
	 */
	private static final class Board {
		
		private final java.util.BitSet filled;
		private int correct;
		private int livesLeft = LIVES;
		private boolean eliminated;
		
		private Board(int cellCount) {
			this.filled = new java.util.BitSet(cellCount);
		}
		
		private @NonNull List<Integer> filledCells() {
			return this.filled.stream().boxed().toList();
		}
	}
}
