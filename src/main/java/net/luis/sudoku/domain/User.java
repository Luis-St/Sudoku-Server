package net.luis.sudoku.domain;

import net.luis.sudoku.permission.Permission;
import net.luis.sudoku.permission.Role;
import org.jspecify.annotations.NonNull;

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
 */
public record User(
	@NonNull UUID id,
	@NonNull String displayName,
	@NonNull Role role,
	@NonNull Instant createdAt,
	boolean revoked
) {
	
	public boolean has(@NonNull Permission permission) {
		// A kicked user keeps their role on paper but must not be able to act on it.
		return !this.revoked && this.role.has(permission);
	}
	
	public boolean isAdmin() {
		return this.role == Role.ADMIN;
	}
}
