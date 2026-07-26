package net.luis.sudoku.error;

import org.jspecify.annotations.NonNull;

import java.util.Map;

/**
 * An error destined for the client, carrying its {@link ErrorCode} and therefore its HTTP status.
 * <p>
 * Throwing one of these from anywhere below a handler is the normal way to fail a request;
 * {@link ErrorHandlerConfig} turns it into the uniform response body.
 */
public class ApiException extends RuntimeException {
	
	private final ErrorCode code;
	private final Map<String, Object> details;
	
	public ApiException(@NonNull ErrorCode code, @NonNull String message) {
		this(code, message, Map.of());
	}
	
	public ApiException(@NonNull ErrorCode code, @NonNull String message, @NonNull Map<String, Object> details) {
		super(message);
		this.code = code;
		this.details = Map.copyOf(details);
	}
	
	public static @NonNull ApiException badRequest(@NonNull String message) {
		return new ApiException(ErrorCode.BAD_REQUEST, message);
	}
	
	public static @NonNull ApiException notFound(@NonNull String message) {
		return new ApiException(ErrorCode.NOT_FOUND, message);
	}
	
	public static @NonNull ApiException unauthorized(@NonNull String message) {
		return new ApiException(ErrorCode.UNAUTHORIZED, message);
	}
	
	// Shorthands for the codes thrown from more than one place.
	
	public static @NonNull ApiException forbidden(@NonNull String message) {
		return new ApiException(ErrorCode.FORBIDDEN, message);
	}
	
	public @NonNull ErrorCode code() {
		return this.code;
	}
	
	public @NonNull Map<String, Object> details() {
		return this.details;
	}
	
	public int status() {
		return this.code.status();
	}
}
