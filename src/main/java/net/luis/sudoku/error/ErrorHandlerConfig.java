package net.luis.sudoku.error;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import net.luis.sudoku.dto.response.ErrorResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Maps every exception that escapes a handler onto the uniform error body (server-spec 13).
 */
public final class ErrorHandlerConfig {
	
	private static final Logger log = LoggerFactory.getLogger(ErrorHandlerConfig.class);
	
	/**
	 * Marks a response whose body this class already wrote.
	 * <p>
	 * Needed because {@link ErrorCode#UNKNOWN_KEY} and {@link ErrorCode#NOT_FOUND} are both 404s: without
	 * the marker, the 404 mapper below would overwrite a deliberate {@code UNKNOWN_KEY} body with a
	 * generic one and clients would lose the distinction.
	 */
	private static final String HANDLED = "sudoku.errorHandled";
	
	private ErrorHandlerConfig() {}
	
	public static void register(@NonNull JavalinConfig config) {
		config.routes.exception(ApiException.class, (e, ctx) -> {
			// Expected, client-visible failures: logged at debug, because a 403 on a stale invite is
			// ordinary traffic rather than an incident.
			log.debug("{} {} -> {}: {}", ctx.method(), ctx.path(), e.code(), e.getMessage());
			respond(ctx, e.code(), e.getMessage(), e.details());
		});
		
		config.routes.exception(IllegalArgumentException.class, (e, ctx) -> {
			// Thrown by shared-core's own validation (checkDigit, checkCellIndex, ofEdgeLength, ...),
			// which makes it a malformed request rather than a server fault.
			log.debug("{} {} -> bad request: {}", ctx.method(), ctx.path(), e.getMessage());
			respond(ctx, ErrorCode.BAD_REQUEST, String.valueOf(e.getMessage()), Map.of());
		});
		
		config.routes.exception(Exception.class, (e, ctx) -> {
			// Anything reaching here is a bug. Log it in full; never leak the detail to the client.
			log.error("Unhandled exception on {} {}", ctx.method(), ctx.path(), e);
			respond(ctx, ErrorCode.INTERNAL, "Internal server error", Map.of());
		});
		
		// Javalin fills unmatched routes with its own problem+json body, which would break the uniform
		// contract. Rewrite it, unless one of the handlers above deliberately produced this 404.
		config.routes.error(HttpStatus.NOT_FOUND.getCode(), "*", ctx -> {
			if (ctx.attribute(HANDLED) == null) {
				respond(ctx, ErrorCode.NOT_FOUND, "No route matched " + ctx.method() + " " + ctx.path(), Map.of());
			}
		});
	}
	
	private static void respond(@NonNull Context ctx, @NonNull ErrorCode code, @Nullable String message, @NonNull Map<String, Object> details) {
		ctx.attribute(HANDLED, Boolean.TRUE);
		ctx.status(code.status());
		ctx.json(new ErrorResponse(code.name(), message == null ? code.name() : message, details));
	}
}
