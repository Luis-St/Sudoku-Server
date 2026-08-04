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
 * Pencil marks are <b>shared</b> here, unlike every other mode: the whole point of co-op is working one
 * board together, and four people privately eliminating the same candidate is four people doing the same
 * work. Spec 10.5 calls notes non-authoritative, which still holds - they are relayed verbatim and never
 * checked against the solution, and only {@link #onPlace} can put a digit on the board.
 */
public final class CoopMatch extends LiveMatch {
	
	/** Spec 11.3: a single pool of five for the whole group, not five each. */
	public static final int SHARED_LIVES = 5;
	
	private final BitSet filled;
	private final int holes;
	private final Map<UUID, Integer> presence = new LinkedHashMap<>();
	/** cell index -&gt; bitmask of noted digits, bit {@code d} for digit {@code d}. Shared by the whole group. */
	private final Map<Integer, Integer> notes = new LinkedHashMap<>();
	
	private int sharedLivesLeft = SHARED_LIVES;
	
	public CoopMatch(@NonNull Match match, @NonNull GeneratedPuzzle puzzle, @NonNull MatchConfig config, @NonNull MatchCallbacks callbacks) {
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
			case NOTE -> this.onNote(userId, payload);
			case HELLO -> {}
			default -> this.sendTo(userId, MessageEnvelope.of(MessageType.ERROR, Map.of("error", "UNSUPPORTED", "message", type + " is not valid in a co-op match")));
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
			this.sendTo(userId, MessageEnvelope.of(MessageType.ERROR, Map.of("error", "BAD_REQUEST", "message", "cell and digit must be valid for this grid")));
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
			// The notes on a solved cell annotate nothing. Dropped here rather than left for the clients to
			// forget individually, so a reconnecting player is not handed candidates for a filled cell.
			this.notes.remove(cell);
		} else if (this.match.livesEnabled()) {
			this.sharedLivesLeft--;
		}
		
		Map<String, Object> result = new HashMap<>();
		result.put("cell", cell);
		result.put("digit", digit);
		result.put("correct", correct);
		result.put("alreadyFilled", false);
		result.put("byUser", userId.toString());
		result.put("livesLeft", this.match.livesEnabled() ? this.sharedLivesLeft : null);
		MessageEnvelope entry = new MessageEnvelope(MessageType.ENTRY_RESULT.name(), 0, System.currentTimeMillis(), result);
		
		if (correct) {
			// A correct entry is the placer's own receipt; what the rest of the group needs is the digit,
			// which the BOARD_UPDATE below carries.
			this.sendTo(userId, entry);
		} else {
			// A *wrong* entry is a shared event, and used to be private. Two things went wrong because of
			// that, both reported by the owner: the shared lives pool is decremented here and only the
			// placer was told, so everybody else's hearts sat at a stale count until a reconnect; and the
			// only thing the others could see of the mistake was the placer's own PRESENCE highlight, which
			// left a cell somebody had just got wrong sitting there in the "somebody is here" colour.
			//
			// Broadcast rather than sent, so one message does both: it carries livesLeft to everyone and it
			// is what each client flashes the cell red from. The placer is included in a broadcast, so they
			// still get exactly one result for their own entry.
			this.broadcast(entry);
		}
		
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
	 * Records and relays one pencil mark.
	 * <p>
	 * Kept server-side rather than only echoed, so that {@link #matchStateMessage} can hand the accumulated
	 * notes to a player who joins late or reconnects - a note that only ever existed as a broadcast would be
	 * invisible to anyone who was not connected when it was made, which is exactly the reconnect case the
	 * protocol is otherwise resynchronising for.
	 * </p>
	 * <p>
	 * A note on a cell that already holds a digit is dropped: the pen layer is authoritative, and a note
	 * under a placed value has nothing to annotate.
	 * </p>
	 */
	private void onNote(@NonNull UUID userId, @NonNull Map<String, Object> payload) {
		if (this.state() != MatchState.RUNNING || this.hasEnded()) {
			return;
		}
		
		Integer cell = MatchPayloads.cell(payload, this.size());
		Integer digit = MatchPayloads.digit(payload, this.size());
		if (cell == null || digit == null || this.filled.get(cell) || this.puzzle.puzzle().cell(cell).isGiven()) {
			return;
		}
		
		boolean add = payload.get("add") instanceof Boolean flag && flag;
		int mask = this.notes.getOrDefault(cell, 0);
		int updated = add ? mask | (1 << digit) : mask & ~(1 << digit);
		if (updated == 0) {
			this.notes.remove(cell);
		} else {
			this.notes.put(cell, updated);
		}
		
		this.broadcast(MessageEnvelope.of(MessageType.NOTE, Map.of(
			"cell", cell,
			"digit", digit,
			"add", add,
			"byUser", userId.toString()
		)));
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
		
		Map<String, Object> noteMap = new LinkedHashMap<>();
		this.notes.forEach((cell, mask) -> noteMap.put(Integer.toString(cell), mask));
		
		Map<String, Object> presenceMap = new LinkedHashMap<>();
		this.presence.forEach((userId, cell) -> presenceMap.put(userId.toString(), cell));
		
		Map<String, Object> payload = new HashMap<>();
		payload.put("matchId", this.id().toString());
		payload.put("mode", this.mode().name());
		payload.put("state", this.state().name());
		payload.put("puzzleKey", MatchPayloads.key(this.match.key()));
		payload.put("board", board);
		payload.put("notes", noteMap);
		payload.put("presence", presenceMap);
		payload.put("livesEnabled", this.match.livesEnabled());
		// A match setting, not a per-player one: everybody on one shared board plays the same game, and a
		// joiner has no other way to learn what the creator configured.
		payload.put("hintsEnabled", this.match.hintsEnabled());
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
