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
	/** {@code { cell, digit, add }} - a private pencil mark, never authoritative (spec 10.5). */
	NOTE(Direction.INBOUND),
	/** {@code { cell }} - co-op only; which cell this player has selected. */
	PRESENCE(Direction.BOTH),
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
