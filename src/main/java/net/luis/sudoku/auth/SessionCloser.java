package net.luis.sudoku.auth;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Closes a user's live WebSocket connections when their session stops being valid.
 * <p>
 * This exists as a seam because sessions are issued long before sockets exist (Phase 3 vs Phase 8).
 * Until the match registry is wired in, {@link #NONE} is a no-op and nothing else has to change.
 */
@FunctionalInterface
public interface SessionCloser {
	
	/**
	 * A closer that does nothing, for use before any socket infrastructure exists.
	 */
	SessionCloser NONE = (userId, reason) -> {};
	
	/**
	 * @param userId the user whose connections should be dropped
	 * @param reason the close reason sent to the client, e.g. {@code SESSION_SUPERSEDED}
	 */
	void closeSocketsFor(@NonNull UUID userId, @NonNull String reason);
}
