package net.luis.sudoku.domain;

import net.luis.sudoku.permission.Permission;
import net.luis.sudoku.permission.Role;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered player.
 *
 * @param id primary key
 * @param displayName unique, chosen at registration
 * @param role drives every permission check (server-spec 7)
 * @param createdAt registration time
 * @param revoked true when kicked; the row is retained so historical results survive (server-spec 7.2)
 * @param email verified recovery address, or null if none has been set
 * @param emailVerified whether {@code email} has completed the verification round-trip
 */
public record User(
	@NonNull UUID id,
	@NonNull String displayName,
	@NonNull Role role,
	@NonNull Instant createdAt,
	boolean revoked,
	@Nullable String email,
	boolean emailVerified
) {
	
	public boolean has(@NonNull Permission permission) {
		// A kicked user keeps their role on paper but must not be able to act on it.
		return !this.revoked && this.role.has(permission);
	}
	
	public boolean isAdmin() {
		return this.role == Role.ADMIN;
	}
}
