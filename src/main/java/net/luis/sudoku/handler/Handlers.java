package net.luis.sudoku.handler;

import io.javalin.http.Context;
import net.luis.sudoku.error.ApiException;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Small helpers shared by handlers.
 */
final class Handlers {
	
	private Handlers() {}
	
	/**
	 * Reads a UUID path parameter.
	 * <p>
	 * A malformed id is a bad request, not a 500 - {@link UUID#fromString} throws
	 * {@link IllegalArgumentException}, which would otherwise surface as a generic 400 without saying
	 * which parameter was wrong.
	 */
	static @NonNull UUID pathUuid(@NonNull Context ctx, @NonNull String name) {
		String raw = ctx.pathParam(name);
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("Path parameter " + name + " must be a UUID, got: " + raw);
		}
	}
	
	/**
	 * The same conversion for a UUID that arrived in a request body rather than the path.
	 */
	static @NonNull UUID uuid(@NonNull String raw, @NonNull String field) {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("Field " + field + " must be a UUID, got: " + raw);
		}
	}
}
