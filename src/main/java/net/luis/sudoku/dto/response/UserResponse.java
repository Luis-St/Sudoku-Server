package net.luis.sudoku.dto.response;

import net.luis.sudoku.domain.User;
import org.jspecify.annotations.NonNull;

/**
 * A user as seen by clients.
 *
 * @param id user id
 * @param displayName the name other players see
 * @param role {@code NEW}, {@code MEMBER} or {@code ADMIN}
 */
public record UserResponse(@NonNull String id, @NonNull String displayName, @NonNull String role) {
	
	public static @NonNull UserResponse of(@NonNull User user) {
		return new UserResponse(user.id().toString(), user.displayName(), user.role().name());
	}
}
