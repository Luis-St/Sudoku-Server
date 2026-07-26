package net.luis.sudoku.permission;

/**
 * The capabilities a role may grant (server-spec 7).
 * <p>
 * Permissions are checked at the action site, never by role name - so a future role change is a change
 * to one mapping table rather than a hunt through handlers for {@code role == ADMIN}.
 */
public enum Permission {
	
	CAN_PLAY,
	CAN_INVITE,
	CAN_KICK,
	CAN_CHANGE_ROLE
}
