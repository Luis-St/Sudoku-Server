package net.luis.sudoku.dto.request;

import net.luis.sudoku.permission.Role;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PATCH /api/v1/users/{id}/role}.
 *
 * @param role {@code NEW}, {@code MEMBER} or {@code ADMIN}
 */
public record ChangeRoleRequest(@Nullable String role) {
	
	public @NonNull Role requireRole() {
		return Role.of(Requests.require(this.role, "role"));
	}
}
