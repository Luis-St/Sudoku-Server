package net.luis.sudoku.dto.request;

import net.luis.sudoku.error.ApiException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Base64;

/**
 * Field validation shared by request DTOs.
 * <p>
 * Every field arrives nullable, because JSON says nothing about presence. These helpers turn absence
 * into a {@code BAD_REQUEST} naming the field, rather than a {@link NullPointerException} deeper down.
 */
public final class Requests {
	
	private Requests() {}
	
	public static @NonNull String require(@Nullable String value, @NonNull String field) {
		if (value == null || value.isBlank()) {
			throw ApiException.badRequest("Missing required field: " + field);
		}
		return value;
	}
	
	public static byte @NonNull [] decodeBase64(@Nullable String value, @NonNull String field) {
		String present = require(value, field);
		
		try {
			// Accept both the standard and URL-safe alphabets: clients differ, and it costs nothing.
			return Base64.getDecoder().decode(present.replace('-', '+').replace('_', '/'));
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("Field " + field + " is not valid Base64");
		}
	}
	
	public static int requirePositive(@Nullable Integer value, @NonNull String field) {
		if (value == null) {
			throw ApiException.badRequest("Missing required field: " + field);
		}
		if (value < 1) {
			throw ApiException.badRequest("Field " + field + " must be positive, got: " + value);
		}
		return value;
	}
}
