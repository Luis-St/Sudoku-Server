package net.luis.sudoku.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A read-only view of the process environment with typed, fail-fast accessors.
 * <p>
 * Indirection over {@link System#getenv} exists so configuration parsing can be unit tested against a
 * plain {@link Map} instead of the real environment.
 */
public final class Env {
	
	private final UnaryOperator<@Nullable String> lookup;
	
	private Env(@NonNull UnaryOperator<@Nullable String> lookup) {
		this.lookup = lookup;
	}
	
	public static @NonNull Env ofSystem() {
		return new Env(System::getenv);
	}
	
	public static @NonNull Env of(@NonNull Map<String, String> values) {
		return new Env(values::get);
	}
	
	/**
	 * Reads a required variable. Blank values count as absent, since an empty string in a compose file
	 * is nearly always an unset secret rather than an intentional value.
	 */
	public @NonNull String require(@NonNull String key) {
		String value = this.lookup.apply(key);
		if (value == null || value.isBlank()) {
			throw new ConfigException("Missing required environment variable: " + key);
		}
		return value;
	}
	
	public @NonNull String string(@NonNull String key, @NonNull String defaultValue) {
		String value = this.lookup.apply(key);
		return value == null || value.isBlank() ? defaultValue : value;
	}
	
	public @Nullable String optional(@NonNull String key) {
		String value = this.lookup.apply(key);
		return value == null || value.isBlank() ? null : value;
	}
	
	public boolean bool(@NonNull String key, boolean defaultValue) {
		String value = this.optional(key);
		if (value == null) {
			return defaultValue;
		}
		
		String normalized = value.trim().toLowerCase();
		return switch (normalized) {
			case "true", "yes", "1", "on" -> true;
			case "false", "no", "0", "off" -> false;
			default -> throw new ConfigException("Environment variable " + key + " must be a boolean, got: " + value);
		};
	}
	
	public int integer(@NonNull String key, int defaultValue) {
		String value = this.optional(key);
		if (value == null) {
			return defaultValue;
		}
		
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			throw new ConfigException("Environment variable " + key + " must be an integer, got: " + value, e);
		}
	}
	
	public double decimal(@NonNull String key, double defaultValue) {
		String value = this.optional(key);
		if (value == null) {
			return defaultValue;
		}
		
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException e) {
			throw new ConfigException("Environment variable " + key + " must be a number, got: " + value, e);
		}
	}
	
	public <E extends Enum<E>> @NonNull E enumeration(@NonNull String key, @NonNull E defaultValue) {
		String value = this.optional(key);
		if (value == null) {
			return defaultValue;
		}
		
		try {
			return Enum.valueOf(defaultValue.getDeclaringClass(), value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ConfigException("Environment variable " + key + " must be one of " + Arrays.toString(defaultValue.getDeclaringClass().getEnumConstants()) + ", got: " + value, e);
		}
	}
}
