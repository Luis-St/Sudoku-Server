package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.ApiVersion;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.config.PresenceConfig;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.dto.response.ErrorResponse;
import net.luis.sudoku.dto.response.HeartbeatResponse;
import net.luis.sudoku.dto.response.MatchRequestResponse;
import net.luis.sudoku.presence.PresenceService;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Online status (server-spec 9.7): a signed-in client reports itself every ten seconds, and everyone's
 * {@code online} flag in {@code /players} is derived from how recently they last did.
 * <p>
 * This is the whole of what {@code WS /ws/v1/presence} used to be. A heartbeat cannot get stuck the way an
 * open socket could - see {@link PresenceService} for why that mattered enough to change.
 */
public class PresenceHandler {

	private final Authentication authentication;
	private final PresenceService presence;
	private final PresenceConfig config;

	public PresenceHandler(@NonNull Authentication authentication, @NonNull PresenceService presence, @NonNull PresenceConfig config) {
		this.authentication = authentication;
		this.presence = presence;
		this.config = config;
	}

	@OpenApi(
		summary = "Report this client as running",
		description = "Called on a timer for as long as the app is in the foreground and signed in; that repetition is "
			+ "what makes the caller show as online to everyone else. The response carries any match requests waiting "
			+ "for them, because this is the one call a client already makes on a timer. A request is not consumed by "
			+ "being read - dismiss it explicitly once it has been answered.",
		operationId = "presenceHeartbeat",
		path = ApiVersion.PATH_PREFIX + "/presence/heartbeat",
		methods = HttpMethod.POST,
		tags = "Presence",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = HeartbeatResponse.class))
	)
	public void heartbeat(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);

		List<MatchRequestResponse> requests = this.presence.heartbeat(actor.userId()).stream()
			.map(MatchRequestResponse::of)
			.toList();
		ctx.json(new HeartbeatResponse(this.config.onlineTtlSeconds(), requests));
	}

	@OpenApi(
		summary = "Go offline now",
		description = "Sign-out and backgrounding call this so the caller stops showing as online immediately rather "
			+ "than when their last heartbeat goes stale. Purely an optimisation of the same outcome: a client that is "
			+ "killed outright never gets here, and lapses on the TTL instead.",
		operationId = "presenceOffline",
		path = ApiVersion.PATH_PREFIX + "/presence/offline",
		methods = HttpMethod.POST,
		tags = "Presence",
		responses = @OpenApiResponse(status = "204")
	)
	public void offline(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		this.presence.goOffline(actor.userId());
		ctx.status(204);
	}

	@OpenApi(
		summary = "Dismiss a match request",
		description = "Called by the invited player once they have accepted or declined. Idempotent: an id that is "
			+ "already gone still answers 204, so a client retrying a dismissal it is unsure landed is not handed a "
			+ "failure for having succeeded.",
		operationId = "dismissMatchRequest",
		path = ApiVersion.PATH_PREFIX + "/match-requests/{id}",
		methods = HttpMethod.DELETE,
		tags = "Presence",
		pathParams = @OpenApiParam(name = "id", description = "Match request id"),
		responses = {
			@OpenApiResponse(status = "204"),
			@OpenApiResponse(status = "403", description = "FORBIDDEN", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void dismissRequest(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		this.presence.dismissRequest(actor.userId(), Handlers.pathUuid(ctx, "id"));
		ctx.status(204);
	}
}
