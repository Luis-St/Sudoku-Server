package net.luis.sudoku.match;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * One participant's live link to a match.
 * <p>
 * An interface rather than a Javalin {@code WsContext} so match logic can be tested without a socket,
 * and so the transport can change without touching the game rules.
 */
public interface Connection {
	
	/**
	 * @return the authenticated player behind this connection
	 */
	@NonNull UUID userId();
	
	/**
	 * The device this socket authenticated with.
	 * <p>
	 * A player may be signed in on several devices at once, so "whose connection is this" is only half
	 * answered by {@link #userId()}: a superseded session concerns one device, and closing the other
	 * one's socket would drop a device that is perfectly fine out of a running match.
	 *
	 * @return the device behind this connection
	 */
	@NonNull UUID deviceId();

	/**
	 * @return their display name, for messages that name a player
	 */
	@NonNull String displayName();
	
	/**
	 * The API version of the path this socket attached on: {@code 1} for {@code /ws/v1/matches/{id}},
	 * {@code 2} for {@code /ws/v2/matches/{id}}.
	 * <p>
	 * A match can legitimately have both attached at once - one player on an old build, one on a new - so
	 * the version belongs to the connection rather than to the match, and {@code MATCH_STATE} is reduced to
	 * the v1 shape for exactly the sockets that asked for it.
	 *
	 * @return The API version this connection speaks, defaulting to the newest
	 */
	default int apiVersion() {
		return net.luis.sudoku.ApiVersion.CURRENT;
	}
	
	void send(@NonNull MessageEnvelope message);
	
	/**
	 * Closes the socket with a reason the client surfaces to the user.
	 */
	void close(@NonNull String reason);
	
	boolean isOpen();
}
