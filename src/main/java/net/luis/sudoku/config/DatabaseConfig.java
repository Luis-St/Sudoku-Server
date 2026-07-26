package net.luis.sudoku.config;

import org.jspecify.annotations.NonNull;

/**
 * Postgres connection settings (server-spec 3, 5.1).
 *
 * @param url JDBC URL, e.g. {@code jdbc:postgresql://db:5432/sudoku}
 * @param user database user
 * @param password database password, supplied from a secret and never committed
 * @param poolSize maximum pooled connections; ~10 is ample for a friends' server
 */
public record DatabaseConfig(@NonNull String url, @NonNull String user, @NonNull String password, int poolSize) {
	
	public DatabaseConfig {
		if (poolSize < 1) {
			throw new ConfigException(EnvKeys.DB_POOL_SIZE + " must be at least 1, got: " + poolSize);
		}
	}
	
	static @NonNull DatabaseConfig from(@NonNull Env env) {
		return new DatabaseConfig(
			env.require(EnvKeys.DB_URL),
			env.require(EnvKeys.DB_USER),
			env.require(EnvKeys.DB_PASSWORD),
			env.integer(EnvKeys.DB_POOL_SIZE, 10)
		);
	}
	
	/**
	 * @return the URL with any embedded credentials stripped, safe to write to a log
	 */
	public @NonNull String safeUrl() {
		int at = this.url.indexOf('@');
		return at < 0 ? this.url : "jdbc:postgresql://***" + this.url.substring(at);
	}
}
