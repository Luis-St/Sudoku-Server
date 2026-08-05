package net.luis.sudoku.match;

import net.luis.sudoku.config.MatchConfig;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.generation.GeneratedPuzzle;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
	/** cell index -&gt; bitmask of noted digits, bit {@code d} for digit {@code d}. Shared by the whole group. */
	private final Map<Integer, Integer> notes = new LinkedHashMap<>();

	private int sharedLivesLeft = SHARED_LIVES;
	/**
	 * The one hint currently on offer, or null. One per match, not one per player: everybody is looking at
	 * the same board, so two hints pointing at two cells would be two groups playing it.
	 */
	private @Nullable Integer hintCell;
	private @Nullable UUID hintOwner;
	
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
			case HINT -> this.onHint(userId, payload);
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
			if (Integer.valueOf(cell).equals(this.hintCell)) {
				// The offer has been taken - by its owner spending it, or by anybody simply solving the cell
				// first, which is just as good an answer to "look here".
				this.clearHint();
			}
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
			// only trace of the mistake the others got was the selection highlight that used to follow every
			// player around, which said "somebody is here" about a cell somebody had just got wrong.
			//
			// That highlight is gone now (see MessageType.HINT), which makes this the *only* thing one player
			// ever sees about another player's cell - so it carries the whole weight of the report.
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
	 * Claims or withdraws the shared hint offer.
	 * <p>
	 * The <i>cell</i> is chosen by the client - shared-core's hint engine runs there, and the server has no
	 * reason to duplicate it. What the server owns is the fact that a hint is pending and whose it is, which
	 * is the part the other players have to see and the part a reconnecting player has to get back.
	 * </p>
	 * <p>
	 * Only one at a time, so a second player cannot move the cell the others are deciding about - but once it
	 * is up, the offer belongs to the group: anybody may withdraw it, and anybody may spend it by placing the
	 * digit. The cap stays per player because the client charges whoever presses reveal, so nothing here has
	 * to know whose it was.
	 * </p>
	 */
	private void onHint(@NonNull UUID userId, @NonNull Map<String, Object> payload) {
		if (this.state() != MatchState.RUNNING || this.hasEnded() || !this.match.hintsEnabled()) {
			return;
		}

		if (payload.get("clear") instanceof Boolean clear && clear) {
			this.clearHint();
			return;
		}

		Integer cell = MatchPayloads.cell(payload, this.size());
		if (cell == null || this.filled.get(cell) || this.puzzle.puzzle().cell(cell).isGiven()) {
			return;
		}
		if (this.hintCell != null) {
			// Somebody is already deciding. The asker is told what the offer actually is rather than being
			// ignored, so a client that missed the broadcast does not sit with a hint button that does nothing.
			this.sendTo(userId, this.hintMessage());
			return;
		}

		this.hintCell = cell;
		this.hintOwner = userId;
		this.broadcast(this.hintMessage());
	}

	/** Drops the pending offer, if there is one, and tells everybody. */
	private void clearHint() {
		if (this.hintCell == null) {
			return;
		}
		this.hintCell = null;
		this.hintOwner = null;
		this.broadcast(this.hintMessage());
	}

	private @NonNull MessageEnvelope hintMessage() {
		Map<String, Object> payload = new HashMap<>();
		payload.put("cell", this.hintCell);
		payload.put("byUser", this.hintOwner == null ? null : this.hintOwner.toString());
		return new MessageEnvelope(MessageType.HINT.name(), 0, System.currentTimeMillis(), payload);
	}

	private void onResign(@NonNull UUID userId) {
		this.participants().remove(userId);
		if (userId.equals(this.hintOwner)) {
			// Their question leaves with them. Anybody left could withdraw it now, but a marked cell nobody
			// present asked about is a mark that means nothing.
			this.clearHint();
		}
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
		
		Map<String, Object> payload = new HashMap<>();
		payload.put("matchId", this.id().toString());
		payload.put("mode", this.mode().name());
		payload.put("state", this.state().name());
		payload.put("puzzleKey", MatchPayloads.key(this.match.key()));
		payload.put("board", board);
		payload.put("notes", noteMap);
		// The pending offer travels in the snapshot as well as in its own broadcast: a player who joins or
		// reconnects while a hint is on the board has to see the same marked cell as everybody else.
		payload.put("hintCell", this.hintCell);
		payload.put("hintBy", this.hintOwner == null ? null : this.hintOwner.toString());
		payload.put("livesEnabled", this.match.livesEnabled());
		// A match setting, not a per-player one: everybody on one shared board plays the same game, and a
		// joiner has no other way to learn what the creator configured.
		payload.put("hintsEnabled", this.match.hintsEnabled());
		payload.put("livesLeft", this.match.livesEnabled() ? this.sharedLivesLeft : null);
		payload.put("participants", MatchPayloads.participants(this.participants().values()));
		
		return new MessageEnvelope(MessageType.MATCH_STATE.name(), 0, System.currentTimeMillis(), payload);
	}
	
	@Override
	protected void onParticipantDisconnected(@NonNull ParticipantState participant) {
		if (participant.userId().equals(this.hintOwner)) {
			// Same reason as a resignation: the question was theirs, and nobody still here asked it. The others
			// could clear it themselves now, but a mark whose asker is gone explains nothing.
			this.clearHint();
		}
	}

	int sharedLivesLeft() {
		return this.sharedLivesLeft;
	}
	
	int filledCount() {
		return this.filled.cardinality();
	}
}
