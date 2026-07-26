package net.luis.sudoku.config;

import org.jspecify.annotations.NonNull;

/**
 * Thrown when the environment is missing a required variable or holds an unusable value.
 * <p>
 * Configuration problems are fatal at startup: the server fails fast rather than booting into a
 * half-configured state (server-spec 3).
 */
public class ConfigException extends RuntimeException {
	
	public ConfigException(@NonNull String message) {
		super(message);
	}
	
	public ConfigException(@NonNull String message, @NonNull Throwable cause) {
		super(message, cause);
	}
}
