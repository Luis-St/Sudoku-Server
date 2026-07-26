package net.luis.sudoku.dto.response;

import net.luis.sudoku.domain.Session;
import net.luis.sudoku.domain.User;
import org.jspecify.annotations.NonNull;

/**
 * The result of registering, linking a device, or completing the challenge handshake.
 *
 * @param sessionToken presented as {@code Authorization: Bearer <token>}
 * @param expiresAt ISO-8601 instant at which the token stops being accepted
 * @param user the authenticated user
 */
public record SessionResponse(@NonNull String sessionToken, @NonNull String expiresAt, @NonNull UserResponse user) {
	
	public static @NonNull SessionResponse of(@NonNull Session session, @NonNull User user) {
		return new SessionResponse(session.token(), session.expiresAt().toString(), UserResponse.of(user));
	}
}
