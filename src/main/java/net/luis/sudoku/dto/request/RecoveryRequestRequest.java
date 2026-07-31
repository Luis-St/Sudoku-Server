package net.luis.sudoku.dto.request;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/auth/recovery/request}. Unauthenticated.
 *
 * @param email the address to look up
 */
public record RecoveryRequestRequest(@Nullable String email) {
	
	public @NonNull String requireEmail() {
		return Requests.require(this.email, "email");
	}
}
