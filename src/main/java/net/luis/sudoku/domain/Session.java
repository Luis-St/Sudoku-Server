package net.luis.sudoku.domain;

import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * An issued session token. At most one exists per user (server-spec 6.2).
 *
 * @param token opaque random string, presented as {@code Authorization: Bearer <token>}
 * @param userId the authenticated user
 * @param deviceId the device that authenticated
 * @param issuedAt when it was issued
 * @param expiresAt when it stops being accepted
 */
public record Session(
	@NonNull String token,
	@NonNull UUID userId,
	@NonNull UUID deviceId,
	@NonNull Instant issuedAt,
	@NonNull Instant expiresAt
) {
	
	public boolean isExpired(@NonNull Instant now) {
		return !this.expiresAt.isAfter(now);
	}
}
