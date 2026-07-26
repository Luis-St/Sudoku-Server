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
	 * @return their display name, for messages that name a player
	 */
	@NonNull String displayName();
	
	void send(@NonNull MessageEnvelope message);
	
	/**
	 * Closes the socket with a reason the client surfaces to the user.
	 */
	void close(@NonNull String reason);
	
	boolean isOpen();
}
