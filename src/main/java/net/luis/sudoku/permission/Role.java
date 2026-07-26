package net.luis.sudoku.permission;

import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import org.jspecify.annotations.NonNull;

import java.util.Set;

/**
 * A user's role and the permissions it carries (server-spec 7).
 * <p>
 * This mapping is the single place role semantics live. Nothing else should compare roles by name.
 */
public enum Role {
	
	/** Freshly registered through an ordinary invite: may play, nothing more. */
	NEW(Set.of(Permission.CAN_PLAY)),
	/** May additionally invite others. */
	MEMBER(Set.of(Permission.CAN_PLAY, Permission.CAN_INVITE)),
	/** Full control: invite, kick, and change roles. */
	ADMIN(Set.of(Permission.CAN_PLAY, Permission.CAN_INVITE, Permission.CAN_KICK, Permission.CAN_CHANGE_ROLE));
	
	private final Set<Permission> permissions;
	
	Role(@NonNull Set<Permission> permissions) {
		this.permissions = Set.copyOf(permissions);
	}
	
	/**
	 * Parses a role name coming from the database or a request body.
	 *
	 * @throws ApiException with {@link ErrorCode#BAD_REQUEST} if the name is not a role
	 */
	public static @NonNull Role of(@NonNull String name) {
		try {
			return valueOf(name.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.BAD_REQUEST, "Unknown role: " + name);
		}
	}
	
	public @NonNull Set<Permission> permissions() {
		return this.permissions;
	}
	
	public boolean has(@NonNull Permission permission) {
		return this.permissions.contains(permission);
	}
}
