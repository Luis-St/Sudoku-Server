package net.luis.sudoku.dto.response;

import net.luis.sudoku.domain.Invite;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * An invite as seen by its creator or an admin.
 *
 * @param code the code to share
 * @param grantsRole role the registrant will receive
 * @param expiresAt ISO-8601 expiry, or null if it never expires
 * @param consumedAt ISO-8601 time it was burned, or null while unused
 * @param revoked whether it has been withdrawn
 */
public record InviteResponse(
	@NonNull String code,
	@NonNull String grantsRole,
	@Nullable String expiresAt,
	@Nullable String consumedAt,
	boolean revoked
) {
	
	public static @NonNull InviteResponse of(@NonNull Invite invite) {
		return new InviteResponse(
			invite.code(),
			invite.grantsRole().name(),
			invite.expiresAt() == null ? null : invite.expiresAt().toString(),
			invite.consumedAt() == null ? null : invite.consumedAt().toString(),
			invite.revoked()
		);
	}
}
