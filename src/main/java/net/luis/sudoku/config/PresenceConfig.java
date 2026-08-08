package net.luis.sudoku.config;

import org.jspecify.annotations.NonNull;

/**
 * Presence and match-request tuning (server-spec 3, feature-spec 9.7).
 * <p>
 * Clients heartbeat every 5 seconds, so {@link #onlineTtlSeconds} must leave room for at least two of
 * those to go missing before a player is called offline. One beat of slack is not enough: a garbage
 * collection pause, a cell handover or a single slow request would make a player who is sitting right
 * there flicker offline, which is the failure everyone notices. The cost of the extra slack is that a
 * genuinely closed app keeps showing online for up to this long, which nobody notices.
 *
 * @param onlineTtlSeconds how long a heartbeat keeps a player online
 * @param matchRequestTtlSeconds how long an undelivered match request stays worth delivering - short,
 *   because it names a specific match that somebody is waiting in
 */
public record PresenceConfig(int onlineTtlSeconds, int matchRequestTtlSeconds) {
	
	public PresenceConfig {
		if (onlineTtlSeconds < 1) {
			throw new ConfigException(EnvKeys.PRESENCE_ONLINE_TTL + " must be at least 1, got: " + onlineTtlSeconds);
		}
		if (matchRequestTtlSeconds < 1) {
			throw new ConfigException(EnvKeys.PRESENCE_REQUEST_TTL + " must be at least 1, got: " + matchRequestTtlSeconds);
		}
	}
	
	static @NonNull PresenceConfig from(@NonNull Env env) {
		return new PresenceConfig(
			env.integer(EnvKeys.PRESENCE_ONLINE_TTL, 15),
			env.integer(EnvKeys.PRESENCE_REQUEST_TTL, 60)
		);
	}
}
