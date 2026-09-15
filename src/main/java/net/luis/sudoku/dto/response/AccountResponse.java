package net.luis.sudoku.dto.response;

import net.luis.sudoku.domain.Device;
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
 * @param forceUpdate whether the <em>calling device</em> is to discard its local copy of the account's
 *   state and take the server's (server-spec 7.3). The one field here that is not about the user: it is
 *   read off {@code devices.force_update} for the device whose key signed this session, and the operator
 *   sets it in SQL. Additive, so no route version moved - a client that does not know the field ignores it
 *   and behaves exactly as before.
 */
public record AccountResponse(@NonNull String id, @NonNull String displayName, @NonNull String role, @Nullable String email, boolean emailVerified, boolean forceUpdate) {
	
	public static @NonNull AccountResponse of(@NonNull User user, @NonNull Device device) {
		return new AccountResponse(user.id().toString(), user.displayName(), user.role().name(), user.email(), user.emailVerified(), device.forceUpdate());
	}
}
