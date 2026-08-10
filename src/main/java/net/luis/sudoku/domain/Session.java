package net.luis.sudoku.domain;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * An issued session token. At most one exists per <em>device</em> (server-spec 6.2).
 * <p>
 * It used to be at most one per user, which is what made linking a second device sign the first one out
 * - and, because the displaced client re-authenticated silently, made the two devices take the session
 * from each other every few seconds for as long as both apps were open.
 *
 * @param token opaque random string, presented as {@code Authorization: Bearer <token>}
 * @param userId the authenticated user
 * @param deviceId the device that authenticated
 * @param issuedAt when it was issued
 * @param expiresAt when it stops being accepted
 * @param supersededToken the token this one replaced for the same device, or null if it replaced none
 * @param supersededAt when that happened, or null if nothing was replaced
 */
public record Session(
	@NonNull String token,
	@NonNull UUID userId,
	@NonNull UUID deviceId,
	@NonNull Instant issuedAt,
	@NonNull Instant expiresAt,
	@Nullable String supersededToken,
	@Nullable Instant supersededAt
) {

	/**
	 * A session that has replaced nothing - what every first sign-in on a device issues.
	 * <p>
	 * A static factory rather than a second constructor: the query builder's row mapper matches a record
	 * against its constructors, and a second one covering a subset of the columns is exactly the
	 * ambiguity it cannot resolve.
	 */
	public static @NonNull Session issued(@NonNull String token, @NonNull UUID userId, @NonNull UUID deviceId, @NonNull Instant issuedAt, @NonNull Instant expiresAt) {
		return new Session(token, userId, deviceId, issuedAt, expiresAt, null, null);
	}

	/**
	 * @return this session, recorded as having replaced {@code previous}
	 */
	public @NonNull Session superseding(@NonNull Session previous, @NonNull Instant now) {
		return new Session(this.token, this.userId, this.deviceId, this.issuedAt, this.expiresAt, previous.token(), now);
	}

	public boolean isExpired(@NonNull Instant now) {
		return !this.expiresAt.isAfter(now);
	}
}
