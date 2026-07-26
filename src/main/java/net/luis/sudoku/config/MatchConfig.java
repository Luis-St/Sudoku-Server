package net.luis.sudoku.config;

import org.jspecify.annotations.NonNull;

/**
 * Match reconnect tuning (server-spec 3, 10.1).
 *
 * @param reconnectGraceSeconds how long a dropped participant may take to return before the match is abandoned
 * @param reconnectLimit reconnects allowed per participant per match
 */
public record MatchConfig(int reconnectGraceSeconds, int reconnectLimit) {
	
	public MatchConfig {
		if (reconnectGraceSeconds < 1) {
			throw new ConfigException(EnvKeys.MATCH_RECONNECT_GRACE + " must be at least 1, got: " + reconnectGraceSeconds);
		}
		if (reconnectLimit < 0) {
			throw new ConfigException(EnvKeys.MATCH_RECONNECT_LIMIT + " must not be negative, got: " + reconnectLimit);
		}
	}
	
	static @NonNull MatchConfig from(@NonNull Env env) {
		return new MatchConfig(
			env.integer(EnvKeys.MATCH_RECONNECT_GRACE, 60),
			env.integer(EnvKeys.MATCH_RECONNECT_LIMIT, 3)
		);
	}
}
