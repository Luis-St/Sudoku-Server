package net.luis.sudoku.config;

/**
 * Every environment variable the server reads, per server-spec 3.
 * <p>
 * Configuration is environment-based only; there is deliberately no configuration file.
 */
public final class EnvKeys {
	
	// Core
	public static final String PORT = "SUDOKU_PORT";
	public static final String SERVER_NAME = "SUDOKU_SERVER_NAME";
	public static final String TIMEZONE = "SUDOKU_TIMEZONE";
	public static final String BOOTSTRAP_INVITE = "SUDOKU_BOOTSTRAP_INVITE";
	
	// Database
	public static final String DB_URL = "SUDOKU_DB_URL";
	public static final String DB_USER = "SUDOKU_DB_USER";
	public static final String DB_PASSWORD = "SUDOKU_DB_PASSWORD";
	public static final String DB_POOL_SIZE = "SUDOKU_DB_POOL_SIZE";
	
	// Daily puzzle
	public static final String DAILY_SIZE = "SUDOKU_DAILY_SIZE";
	
	/**
	 * Whether {@code X-Real-IP} / {@code X-Forwarded-For} may be believed.
	 * <p>
	 * True for the documented deployment, where a reverse proxy terminates TLS in front of the server.
	 * Set false only if the server is directly reachable, where those headers are client-controlled.
	 */
	public static final String TRUST_PROXY = "SUDOKU_TRUST_PROXY";
	
	// Duel mode
	public static final String DUEL_INITIAL_BANK = "SUDOKU_DUEL_INITIAL_BANK";
	public static final String DUEL_GAIN_CORRECT = "SUDOKU_DUEL_GAIN_CORRECT";
	public static final String DUEL_LOSS_INCORRECT = "SUDOKU_DUEL_LOSS_INCORRECT";
	public static final String DUEL_MAX_BANK = "SUDOKU_DUEL_MAX_BANK";
	public static final String DUEL_MIN_TURN = "SUDOKU_DUEL_MIN_TURN";
	public static final String DUEL_REGEN_RATIO = "SUDOKU_DUEL_REGEN_RATIO";
	public static final String DUEL_MAX_HANDOVERS = "SUDOKU_DUEL_MAX_HANDOVERS";
	
	// Match reconnect
	public static final String MATCH_RECONNECT_GRACE = "SUDOKU_MATCH_RECONNECT_GRACE";
	public static final String MATCH_RECONNECT_LIMIT = "SUDOKU_MATCH_RECONNECT_LIMIT";
	
	// Currency
	public static final String CURRENCY_DAILY_GAME_CAP = "SUDOKU_CURRENCY_DAILY_GAME_CAP";
	
	// Mail (account recovery); unset means mail is not configured
	public static final String SMTP_HOST = "SUDOKU_SMTP_HOST";
	public static final String SMTP_PORT = "SUDOKU_SMTP_PORT";
	public static final String SMTP_SECURITY = "SUDOKU_SMTP_SECURITY";
	public static final String SMTP_USERNAME = "SUDOKU_SMTP_USERNAME";
	public static final String SMTP_PASSWORD = "SUDOKU_SMTP_PASSWORD";
	public static final String SMTP_FROM = "SUDOKU_SMTP_FROM";
	
	private EnvKeys() {}
}
