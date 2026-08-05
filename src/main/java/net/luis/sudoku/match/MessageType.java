package net.luis.sudoku.match;

import net.luis.sudoku.error.ApiException;
import org.jspecify.annotations.NonNull;

/**
 * Every WebSocket message type (server-spec 10.3).
 */
public enum MessageType {
	
	// Client -> server
	/** {@code { clientGenVersion }} - the compatibility gate, sent first. */
	HELLO(Direction.INBOUND),
	/** Participant declares itself ready; the match starts once all are. */
	READY(Direction.INBOUND),
	/** {@code { cell, digit }} - commit a pen value. */
	PLACE(Direction.INBOUND),
	/**
	 * {@code { cell, digit, add }} - a pencil mark, never authoritative (spec 10.5).
	 * <p>
	 * {@link Direction#BOTH} because co-op shares its notes: the owner asked for pencil marks to be visible
	 * to the other players, since a shared board that everybody annotates privately means the same
	 * elimination is worked out four times over. {@link RaceMatch} and {@link DuelMatch} still drop it -
	 * their notes stay private, and race in particular must never emit cell content (spec 11.1).
	 * </p>
	 */
	NOTE(Direction.BOTH),
	/**
	 * {@code { cell }} to ask, {@code { clear: true }} to withdraw - co-op only; the shared hint offer.
	 * <p>
	 * {@link Direction#BOTH} and held by the match rather than each client, because a hint on a shared board
	 * is a statement about the board: everybody sees the offered cell marked, so the group can decide
	 * together and nobody fills it from under the player who asked. Broadcast back as
	 * {@code { cell, byUser }}, or {@code { cell: null }} once it is spent or withdrawn.
	 * </p>
	 * <p>
	 * This replaced {@code PRESENCE}, which broadcast which cell each player had merely *selected*. The
	 * owner had it removed: a highlight on every tap marked cells nothing had happened to, and the two
	 * things worth seeing about somebody else's cell are that they got it wrong (which
	 * {@link #ENTRY_RESULT} carries) and that they are asking about it - this.
	 * </p>
	 */
	HINT(Direction.BOTH),
	/** Give up. */
	RESIGN(Direction.INBOUND),
	/** Duel only: the app was backgrounded, which forfeits immediately (spec 11.2). */
	BACKGROUNDED(Direction.INBOUND),
	
	// Server -> client
	/** Full snapshot, sent on connect and every reconnect; clients replace state wholesale. */
	MATCH_STATE(Direction.OUTBOUND),
	/** {@code { cell, digit, correct, livesLeft }}. */
	ENTRY_RESULT(Direction.OUTBOUND),
	/** {@code { cell, digit, byUser }} - duel and co-op, where the pen layer is shared. */
	BOARD_UPDATE(Direction.OUTBOUND),
	/** {@code { userId, handoverNo }} - duel. */
	CONTROL_CHANGED(Direction.OUTBOUND),
	/** {@code { userId, remainingMs }} - duel, broadcast at about 1Hz; clients interpolate. */
	BANK_UPDATE(Direction.OUTBOUND),
	/** {@code { userId, filledPercent }} - race. Never cell content (spec 11.1). */
	PROGRESS(Direction.OUTBOUND),
	/** {@code { winnerId, reason }}. */
	MATCH_ENDED(Direction.OUTBOUND),
	/** Acknowledges the highest processed client {@code seq} (spec 10.2). */
	ACK(Direction.OUTBOUND),
	/** {@code { error, message }} - a protocol or validation failure that does not close the socket. */
	ERROR(Direction.OUTBOUND);
	
	private final Direction direction;
	
	MessageType(@NonNull Direction direction) {
		this.direction = direction;
	}
	
	public static @NonNull MessageType of(@NonNull String name) {
		try {
			return valueOf(name.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("Unknown message type: " + name);
		}
	}
	
	public boolean acceptsFromClient() {
		return this.direction == Direction.INBOUND || this.direction == Direction.BOTH;
	}
	
	/**
	 * Which way a message may legitimately travel. Enforced on receipt so a client cannot inject a
	 * server-authored type such as {@code MATCH_ENDED}.
	 */
	public enum Direction {
		INBOUND, OUTBOUND, BOTH
	}
}
