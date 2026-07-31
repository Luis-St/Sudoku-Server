package net.luis.sudoku.presence;

import org.jspecify.annotations.NonNull;

/**
 * One open presence socket, transport-free so {@link PresenceService} can be exercised without a
 * running Javalin - the same seam the match logic uses for its own connections.
 */
public interface PresenceConnection {
	
	void send(@NonNull PresenceMessage message);
	
	boolean isOpen();
}
