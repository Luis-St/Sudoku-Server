package net.luis.sudoku.config;

import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import org.jspecify.annotations.NonNull;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * The fully parsed server configuration (server-spec 3).
 * <p>
 * Built once at startup from the environment and passed down explicitly; nothing reads
 * {@link System#getenv} outside this package.
 * <p>
 * There is no TLS configuration: the server always speaks plain HTTP and a reverse proxy terminates
 * TLS in front of it (server-spec 4). See the nginx sample in the README. Parsing is fail-fast, so a misconfigured deployment
 * dies at boot with a precise message instead of failing later on the first request that needs the
 * value.
 *
 * @param port listen port
 * @param serverName display name shown to clients
 * @param timezone IANA zone driving daily rollover and streak evaluation
 * @param bootstrapInvite invite code granting the very first admin
 * @param database Postgres connection settings
 * @param dailySize grid size of the daily puzzle
 * @param trustProxy whether proxy-supplied client-address headers may be believed
 * @param duel duel-mode time-bank tuning
 * @param match match reconnect tuning
 * @param currencyDailyGameCap currency-earning normal games per day
 */
public record ServerConfig(
	int port,
	@NonNull String serverName,
	@NonNull ZoneId timezone,
	@NonNull String bootstrapInvite,
	@NonNull DatabaseConfig database,
	@NonNull GridSize dailySize,
	boolean trustProxy,
	@NonNull DuelConfig duel,
	@NonNull MatchConfig match,
	int currencyDailyGameCap
) {
	
	/**
	 * The daily puzzle is always classic; this is fixed rather than configurable (server-spec 3).
	 */
	public static final Variant DAILY_VARIANT = Variant.CLASSIC;
	
	public ServerConfig {
		if (port < 1 || port > 65535) {
			throw new ConfigException(EnvKeys.PORT + " must be a valid port number, got: " + port);
		}
		if (currencyDailyGameCap < 0) {
			throw new ConfigException(EnvKeys.CURRENCY_DAILY_GAME_CAP + " must not be negative, got: " + currencyDailyGameCap);
		}
	}
	
	public static @NonNull ServerConfig fromEnvironment() {
		return from(Env.ofSystem());
	}
	
	public static @NonNull ServerConfig from(@NonNull Env env) {
		return new ServerConfig(
			env.integer(EnvKeys.PORT, 7000),
			env.string(EnvKeys.SERVER_NAME, "Sudoku Server"),
			parseZone(env.string(EnvKeys.TIMEZONE, "UTC")),
			env.require(EnvKeys.BOOTSTRAP_INVITE),
			DatabaseConfig.from(env),
			parseDailySize(env.integer(EnvKeys.DAILY_SIZE, 9)),
			env.bool(EnvKeys.TRUST_PROXY, true),
			DuelConfig.from(env),
			MatchConfig.from(env),
			env.integer(EnvKeys.CURRENCY_DAILY_GAME_CAP, 10)
		);
	}
	
	private static @NonNull ZoneId parseZone(@NonNull String value) {
		try {
			return ZoneId.of(value);
		} catch (DateTimeException e) {
			throw new ConfigException(EnvKeys.TIMEZONE + " must be an IANA zone id, got: " + value, e);
		}
	}
	
	private static @NonNull GridSize parseDailySize(int edgeLength) {
		try {
			return GridSize.ofEdgeLength(edgeLength);
		} catch (IllegalArgumentException e) {
			throw new ConfigException(EnvKeys.DAILY_SIZE + " must be a supported grid edge length (4, 6, 9, 12, 16), got: " + edgeLength, e);
		}
	}
	
	/**
	 * @return the fixed daily variant, exposed as an accessor so callers need not reach for the constant
	 */
	public @NonNull Variant dailyVariant() {
		return DAILY_VARIANT;
	}
}
