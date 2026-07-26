package net.luis.sudoku.match;

import net.luis.sudoku.config.MatchConfig;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.generation.GeneratedPuzzle;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * Co-operative mode (server-spec 11.3).
 * <p>
 * Up to four participants share the pen layer. Entries are serialised through the match queue, so two
 * players typing into the same cell at the same instant resolve by arrival order: the first correct
 * entry wins, and the second is told the cell was already filled rather than being punished for it.
 * <p>
 * Pencil marks stay private per player (spec 10.5); only the pen layer is shared.
 */
public final class CoopMatch extends LiveMatch {
	
	/** Spec 11.3: a single pool of five for the whole group, not five each. */
	public static final int SHARED_LIVES = 5;
	
	private final BitSet filled;
	private final int holes;
	private final Map<UUID, Integer> presence = new LinkedHashMap<>();
	
	private int sharedLivesLeft = SHARED_LIVES;
	
	public CoopMatch(@NonNull Match match, @NonNull GeneratedPuzzle puzzle, @NonNull MatchConfig config,
	                 @NonNull MatchCallbacks callbacks) {
		super(match, puzzle, config, callbacks);
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
			case PRESENCE -> this.onPresence(userId, payload);
			case RESIGN -> this.onResign(userId);
			case NOTE, HELLO -> {}
			default -> this.sendTo(userId, MessageEnvelope.of(MessageType.ERROR,
				Map.of("error", "UNSUPPORTED", "message", type + " is not valid in a co-op match")));
		}
	}
	
	private void onReady(@NonNull UUID userId) {
		ParticipantState participant = this.participant(userId);
		if (participant == null || this.state() == MatchState.RUNNING) {
			return;
		}
		participant.ready = true;
		// Co-op admits up to four but need not be full to start, so any two ready players suffice.
		if (this.participants().size() >= 2 && this.participants().values().stream().allMatch(p -> p.ready)) {
			this.start();
		}
	}
	
	private void onPlace(@NonNull UUID userId, @NonNull Map<String, Object> payload) {
		if (this.state() != MatchState.RUNNING || this.hasEnded()) {
			return;
		}
		
		Integer cell = MatchPayloads.cell(payload, this.size());
		Integer digit = MatchPayloads.digit(payload, this.size());
		if (cell == null || digit == null) {
			this.sendTo(userId, MessageEnvelope.of(MessageType.ERROR,
				Map.of("error", "BAD_REQUEST", "message", "cell and digit must be valid for this grid")));
			return;
		}
		if (this.puzzle.puzzle().cell(cell).isGiven()) {
			return;
		}
		
		if (this.filled.get(cell)) {
			// The losing half of a race for one cell. Not a mistake, and it must not cost a life -
			// the client shows brief, non-alarming feedback (spec 11.3).
			Map<String, Object> already = new HashMap<>();
			already.put("cell", cell);
			already.put("digit", digit);
			already.put("correct", true);
			already.put("alreadyFilled", true);
			already.put("livesLeft", this.match.livesEnabled() ? this.sharedLivesLeft : null);
			this.sendTo(userId, new MessageEnvelope(MessageType.ENTRY_RESULT.name(), 0, System.currentTimeMillis(), already));
			return;
		}
		
		boolean correct = this.puzzle.solutionAt(cell) == digit;
		if (correct) {
			this.filled.set(cell);
		} else if (this.match.livesEnabled()) {
			this.sharedLivesLeft--;
		}
		
		Map<String, Object> result = new HashMap<>();
		result.put("cell", cell);
		result.put("digit", digit);
		result.put("correct", correct);
		result.put("alreadyFilled", false);
		result.put("livesLeft", this.match.livesEnabled() ? this.sharedLivesLeft : null);
		this.sendTo(userId, new MessageEnvelope(MessageType.ENTRY_RESULT.name(), 0, System.currentTimeMillis(), result));
		
		if (correct) {
			this.broadcast(MessageEnvelope.of(MessageType.BOARD_UPDATE, Map.of(
				"cell", cell,
				"digit", digit,
				"byUser", userId.toString()
			)));
		}
		
		if (this.filled.cardinality() == this.holes) {
			// Everyone wins together, so there is no single winner id to report.
			this.endMatch(null, EndReason.COMPLETED);
			return;
		}
		if (this.match.livesEnabled() && this.sharedLivesLeft <= 0) {
			this.endMatch(null, EndReason.LIVES_EXHAUSTED);
		}
	}
	
	/**
	 * Broadcasts which cell a player has selected, so the group can avoid colliding in the first place
	 * (spec 11.3).
	 */
	private void onPresence(@NonNull UUID userId, @NonNull Map<String, Object> payload) {
		Integer cell = MatchPayloads.cell(payload, this.size());
		if (cell == null) {
			return;
		}
		this.presence.put(userId, cell);
		this.broadcast(MessageEnvelope.of(MessageType.PRESENCE, Map.of(
			"userId", userId.toString(),
			"cell", cell
		)));
	}
	
	private void onResign(@NonNull UUID userId) {
		this.participants().remove(userId);
		this.presence.remove(userId);
		if (this.participants().size() < 2) {
			this.endMatch(null, EndReason.RESIGNED);
		} else {
			this.broadcastAll();
		}
	}
	
	@Override
	protected @NonNull MessageEnvelope matchStateMessage(@NonNull UUID forUser) {
		Map<String, Object> board = new LinkedHashMap<>();
		this.filled.stream().forEach(cell -> board.put(Integer.toString(cell), this.puzzle.solutionAt(cell)));
		
		Map<String, Object> presenceMap = new LinkedHashMap<>();
		this.presence.forEach((userId, cell) -> presenceMap.put(userId.toString(), cell));
		
		Map<String, Object> payload = new HashMap<>();
		payload.put("matchId", this.id().toString());
		payload.put("mode", this.mode().name());
		payload.put("state", this.state().name());
		payload.put("puzzleKey", MatchPayloads.key(this.match.key()));
		payload.put("board", board);
		payload.put("presence", presenceMap);
		payload.put("livesEnabled", this.match.livesEnabled());
		payload.put("livesLeft", this.match.livesEnabled() ? this.sharedLivesLeft : null);
		payload.put("participants", MatchPayloads.participants(this.participants().values()));
		
		return new MessageEnvelope(MessageType.MATCH_STATE.name(), 0, System.currentTimeMillis(), payload);
	}
	
	int sharedLivesLeft() {
		return this.sharedLivesLeft;
	}
	
	int filledCount() {
		return this.filled.cardinality();
	}
}
