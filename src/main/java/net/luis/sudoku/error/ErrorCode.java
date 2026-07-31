package net.luis.sudoku.error;

/**
 * The stable error vocabulary shared with clients (server-spec 13).
 * <p>
 * The name is the contract - clients branch on it - so these constants must never be renamed once
 * released. The HTTP status travels with the code so a handler cannot accidentally pair a code with
 * the wrong status.
 */
public enum ErrorCode {
	
	// Authentication
	/** Public key not registered. */
	UNKNOWN_KEY(404),
	/** Challenge verification failed: bad signature, or an expired/reused nonce. */
	INVALID_SIGNATURE(401),
	/** Displaced by a newer session. */
	SESSION_SUPERSEDED(401),
	/** No or malformed credentials. */
	UNAUTHORIZED(401),
	
	// Registration and linking
	/** Invite missing, expired, revoked, or already consumed. */
	INVITE_INVALID(403),
	/** Bootstrap claim attempted after an admin already exists. */
	ADMIN_EXISTS(403),
	/** Display name already in use. */
	NAME_TAKEN(409),
	/**
	 * Public key already registered to a device. Not in the spec's table, but spec 6.3 requires a 409
	 * for a taken public key and {@link #NAME_TAKEN} would misreport which value collided.
	 */
	KEY_TAKEN(409),
	/** Link code missing, expired, or already consumed. */
	LINK_CODE_INVALID(403),
	
	// Account recovery
	/** That email is already verified on another account. */
	EMAIL_TAKEN(409),
	/** Email verification code missing, expired, or already consumed. */
	EMAIL_VERIFICATION_INVALID(403),
	/** Recovery code missing, expired, or already consumed. */
	RECOVERY_CODE_INVALID(403),
	/** No SMTP settings are configured on this server. */
	MAIL_NOT_CONFIGURED(503),
	
	// Authorization
	/** The caller has been kicked. */
	USER_REVOKED(403),
	/** The caller lacks the permission this action requires. */
	FORBIDDEN(403),
	/** The operation would leave the server with zero non-revoked admins. */
	LAST_ADMIN(409),
	
	// Compatibility
	/** The client's shared-core would generate different puzzles; it must update. */
	GEN_VERSION_MISMATCH(426),
	
	// Match state
	MATCH_FULL(409),
	NOT_YOUR_TURN(409),
	/** Lisa requested for a multiplayer match; it is single-player only. */
	LISA_NOT_ALLOWED(400),
	/** Balance is below the match stake. */
	INSUFFICIENT_BALANCE(409),
	/** A match request was addressed to a player with no presence socket open. */
	PLAYER_OFFLINE(409),
	
	// Daily
	/** A success already exists for that date; the date is locked. */
	DAILY_ALREADY_SOLVED(409),
	/** Submission for a past or future date. */
	DAILY_DATE_INVALID(409),
	/** There is no gap to restore, or the streak has never been started. */
	STREAK_RESTORE_NOT_NEEDED(409),
	/** Fewer banked restore points than the number of missed days. */
	INSUFFICIENT_RESTORE_POINTS(409),
	
	// Generic
	BAD_REQUEST(400),
	NOT_FOUND(404),
	CONFLICT(409),
	RATE_LIMITED(429),
	INTERNAL(500);
	
	private final int status;
	
	ErrorCode(int status) {
		this.status = status;
	}
	
	public int status() {
		return this.status;
	}
}
