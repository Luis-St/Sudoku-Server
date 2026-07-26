package net.luis.sudoku.domain;

import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.permission.Permission;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * The authenticated caller behind a request: who they are, and which device they used.
 * <p>
 * Attached to the Javalin context by the bearer-token filter and read by handlers.
 *
 * @param user the authenticated user
 * @param device the device whose key signed the challenge
 * @param session the session token in play
 */
public record Principal(@NonNull User user, @NonNull Device device, @NonNull Session session) {
	
	/**
	 * Enforces a permission at the action site (server-spec 7).
	 *
	 * @throws ApiException with {@link ErrorCode#FORBIDDEN} if the permission is missing
	 */
	public void require(@NonNull Permission permission) {
		if (!this.user.has(permission)) {
			throw new ApiException(ErrorCode.FORBIDDEN, "This action requires " + permission);
		}
	}
	
	public boolean has(@NonNull Permission permission) {
		return this.user.has(permission);
	}
	
	public @NonNull UUID userId() {
		return this.user.id();
	}
	
	public @NonNull UUID deviceId() {
		return this.device.id();
	}
}
