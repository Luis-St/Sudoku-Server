package net.luis.sudoku.domain;

import net.luis.sudoku.permission.Role;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * An invite code granting registration at a given role.
 *
 * @param code the Base32 code itself, and the primary key
 * @param createdBy issuing user, null for the bootstrap invite which predates every user
 * @param grantsRole role the registrant receives - {@code NEW} normally, {@code ADMIN} for bootstrap
 * @param expiresAt optional expiry
 * @param consumedByDevice device that burned it, null while unused
 * @param consumedAt when it was burned, null while unused
 * @param revoked true once withdrawn by its creator or an admin
 * @param createdAt when the invite was issued
 */
public record Invite(
	@NonNull String code,
	@Nullable UUID createdBy,
	@NonNull Role grantsRole,
	@Nullable Instant expiresAt,
	@Nullable UUID consumedByDevice,
	@Nullable Instant consumedAt,
	boolean revoked,
	@NonNull Instant createdAt
) {
	
	/**
	 * @return whether this invite may still be redeemed at {@code now}
	 */
	public boolean isLive(@NonNull Instant now) {
		return !this.revoked && this.consumedAt == null && (this.expiresAt == null || this.expiresAt.isAfter(now));
	}
	
	public boolean isBootstrap() {
		return this.grantsRole == Role.ADMIN;
	}
}
