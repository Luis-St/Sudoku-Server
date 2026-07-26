package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.dto.response.HealthResponse;
import org.jspecify.annotations.NonNull;

import java.util.function.IntSupplier;

/**
 * Liveness endpoint (server-spec 14).
 * <p>
 * The active-match count is supplied rather than read directly, so Phase 8's match registry can be
 * plugged in without touching this class; until then it reports zero.
 */
public class HealthHandler {
	
	private final int schemaVersion;
	private final IntSupplier activeMatchCount;
	
	public HealthHandler(int schemaVersion, @NonNull IntSupplier activeMatchCount) {
		this.schemaVersion = schemaVersion;
		this.activeMatchCount = activeMatchCount;
	}
	
	@OpenApi(
		summary = "Health check",
		operationId = "healthCheck",
		path = "/health",
		methods = HttpMethod.GET,
		tags = "Health",
		responses = @OpenApiResponse(
			status = "200",
			content = @OpenApiContent(from = HealthResponse.class)
		)
	)
	public void health(@NonNull Context ctx) {
		ctx.json(new HealthResponse("UP", this.schemaVersion, this.activeMatchCount.getAsInt()));
	}
}
