package net.luis.sudoku.dto.response;

import net.luis.sudoku.domain.User;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The caller's own account, as only the caller may see it.
 * <p>
 * Deliberately not {@link UserResponse} with two extra fields: {@code email} is the caller's alone, and a
 * record shared with {@code /users} would be one careless reuse away from publishing every player's
 * address. This one is only ever built from the authenticated principal.
 *
 * @param id user id
 * @param displayName the name other players see
 * @param role {@code NEW}, {@code MEMBER} or {@code ADMIN} - live, not what it was at sign-in
 * @param email the recovery address on file, or null if none has been set
 * @param emailVerified whether {@code email} has completed the verification round-trip
 */
public record AccountResponse(@NonNull String id, @NonNull String displayName, @NonNull String role, @Nullable String email, boolean emailVerified) {

	public static @NonNull AccountResponse of(@NonNull User user) {
		return new AccountResponse(user.id().toString(), user.displayName(), user.role().name(), user.email(), user.emailVerified());
	}
}
