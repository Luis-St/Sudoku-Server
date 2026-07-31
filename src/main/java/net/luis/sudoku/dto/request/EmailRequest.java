package net.luis.sudoku.dto.request;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/users/me/email}.
 *
 * @param email the address to verify
 */
public record EmailRequest(@Nullable String email) {
	
	public @NonNull String requireEmail() {
		return Requests.require(this.email, "email");
	}
}
