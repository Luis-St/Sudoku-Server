package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.dto.response.HealthResponse;
import org.jspecify.annotations.NonNull;

public class HealthHandler {
	
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
		ctx.json(new HealthResponse("UP"));
	}
}
