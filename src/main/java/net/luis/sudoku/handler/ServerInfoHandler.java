package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.ApiVersion;
import net.luis.sudoku.config.ServerConfig;
import net.luis.sudoku.dto.response.ServerInfoResponse;
import net.luis.sudoku.version.GenVersion;
import org.jspecify.annotations.NonNull;

/**
 * Serves the unauthenticated server description a client fetches before connecting.
 */
public class ServerInfoHandler {
	
	private final ServerInfoResponse response;
	
	public ServerInfoHandler(@NonNull ServerConfig config, @NonNull String serverId) {
		// Every field is fixed for the process lifetime, so the response is built once.
		this.response = new ServerInfoResponse(
			serverId,
			config.serverName(),
			config.timezone().getId(),
			config.dailySize().n(),
			config.dailyVariant().name(),
			GenVersion.CURRENT,
			ApiVersion.CURRENT
		);
	}
	
	@OpenApi(
		summary = "Server description",
		description = "Unauthenticated. Clients call this first and refuse to connect on a genVersion mismatch.",
		operationId = "serverInfo",
		path = "/api/v1/server-info",
		methods = HttpMethod.GET,
		tags = "Server",
		responses = @OpenApiResponse(
			status = "200",
			content = @OpenApiContent(from = ServerInfoResponse.class)
		)
	)
	public void serverInfo(@NonNull Context ctx) {
		ctx.json(this.response);
	}
}
