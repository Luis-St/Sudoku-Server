package net.luis.sudoku;

import io.javalin.Javalin;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import net.luis.sudoku.handler.HealthHandler;
import org.jspecify.annotations.NonNull;
import org.slf4j.*;

import java.util.UUID;

public class Application {
	
	private static final Logger log = LoggerFactory.getLogger(Application.class);
	
	static void main() {
		int port = Integer.parseInt(env("SUDOKU_PORT", "7070"));
		
		var healthHandler = new HealthHandler();
		
		Javalin app = Javalin.create(config -> {
			config.startup.showJavalinBanner = false;
			config.http.defaultContentType = "application/json";
			
			config.registerPlugin(new OpenApiPlugin(pluginConfig ->
				pluginConfig.withDefinitionConfiguration((_, definition) ->
					definition.info(info ->
						info.title("Sudoku Server").version("1.0.0").description("Sudoku puzzle generation and solving API")
					)
				)
			));
			config.registerPlugin(new SwaggerPlugin());
			
			config.routes.before(ctx -> {
				String traceId = UUID.randomUUID().toString();
				MDC.put("trace_id", traceId);
				MDC.put("source_ip", ctx.ip());
				ctx.attribute("trace_id", traceId);
			});
			
			config.routes.after(ctx -> {
				log.info("{} {} {}", ctx.method(), ctx.path(), ctx.status());
				MDC.clear();
			});
			
			// Health
			config.routes.get("/health", healthHandler::health);
		});
		
		app.start(port);
		log.info("Sudoku Server started on port {}", port);
	}
	
	private static @NonNull String env(@NonNull String key, @NonNull String defaultValue) {
		String value = System.getenv(key);
		return value != null ? value : defaultValue;
	}
}
