package net.luis.sudoku.dto.request;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/users/me/email/verify}.
 *
 * @param code the 6-digit code emailed to the address being verified
 */
public record EmailVerifyRequest(@Nullable String code) {
	
	public @NonNull String requireCode() {
		return Requests.require(this.code, "code");
	}
}
