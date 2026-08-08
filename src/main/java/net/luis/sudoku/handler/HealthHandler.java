package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.*;
import net.luis.sudoku.dto.response.HealthResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Liveness endpoint (server-spec 14).
 * <p>
 * Both numbers and the database probe are supplied rather than read directly, so this class stays
 * constructible without a service graph behind it.
 * <p>
 * The probe is the point. Answering {@code UP} purely because the HTTP thread is running says nothing:
 * every route on this server needs the database, so a process whose pool has died still answers, still
 * passes the container's health check, and still fails every actual request. Reporting that as healthy
 * is worse than not reporting at all, because it is the signal that decides whether the deployment gets
 * restarted.
 */
public class HealthHandler {
	
	private static final Logger log = LoggerFactory.getLogger(HealthHandler.class);
	
	private final int schemaVersion;
	private final IntSupplier activeMatchCount;
	private final BooleanSupplier databaseReachable;
	
	/**
	 * What the last probe found, so the transitions get logged rather than every single probe.
	 * <p>
	 * The health check runs on a timer: logging each failure would write the same line every 30 seconds
	 * for as long as the outage lasts, and the useful facts are only ever "it went down" and "it came
	 * back". Starts optimistic, because the graph could not have been built against an unreachable
	 * database in the first place.
	 */
	private final AtomicBoolean reachable = new AtomicBoolean(true);
	
	public HealthHandler(int schemaVersion, @NonNull IntSupplier activeMatchCount, @NonNull BooleanSupplier databaseReachable) {
		this.schemaVersion = schemaVersion;
		this.activeMatchCount = activeMatchCount;
		this.databaseReachable = databaseReachable;
	}
	
	@OpenApi(
		summary = "Health check",
		operationId = "healthCheck",
		path = "/health",
		methods = HttpMethod.GET,
		tags = "Health",
		responses = {
			@OpenApiResponse(
				status = "200",
				description = "The server is serving and the database answered",
				content = @OpenApiContent(from = HealthResponse.class)
			),
			@OpenApiResponse(
				status = "503",
				description = "The database did not answer; the same body, with status DOWN",
				content = @OpenApiContent(from = HealthResponse.class)
			)
		}
	)
	public void health(@NonNull Context ctx) {
		boolean up = this.databaseReachable.getAsBoolean();
		if (this.reachable.getAndSet(up) != up) {
			if (up) {
				log.warn("Database answered again, /health is back to UP");
			} else {
				log.warn("Database did not answer, /health now reports DOWN and every request needing it will fail");
			}
		}
		
		ctx.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE);
		ctx.json(new HealthResponse(up ? "UP" : "DOWN", this.schemaVersion, this.activeMatchCount.getAsInt()));
	}
}
