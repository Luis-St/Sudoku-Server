package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.ApiVersion;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.dto.request.StatsSyncRequest;
import net.luis.sudoku.dto.response.*;
import net.luis.sudoku.presence.PresenceService;
import net.luis.sudoku.stats.StatsService;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Player browsing and statistics (server-spec 9).
 */
public class StatsHandler {
	
	private final Authentication authentication;
	private final StatsService stats;
	private final PresenceService presence;
	
	public StatsHandler(@NonNull Authentication authentication, @NonNull StatsService stats, @NonNull PresenceService presence) {
		this.authentication = authentication;
		this.stats = stats;
		this.presence = presence;
	}
	
	@OpenApi(
		summary = "Browse players",
		operationId = "listPlayers",
		path = ApiVersion.PATH_PREFIX + "/players",
		methods = HttpMethod.GET,
		tags = "Stats",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PlayerResponse[].class))
	)
	public void players(@NonNull Context ctx) {
		this.authentication.require(ctx);
		List<PlayerResponse> players = this.stats.players().stream()
			.map(summary -> PlayerResponse.of(summary, this.presence.isOnline(summary.id())))
			.toList();
		ctx.json(players);
	}
	
	@OpenApi(
		summary = "A player's aggregate statistics",
		description = "Grouped by difficulty tier, because solve times are only comparable within a tier.",
		operationId = "playerStats",
		path = ApiVersion.PATH_PREFIX + "/players/{id}/stats",
		methods = HttpMethod.GET,
		tags = "Stats",
		pathParams = @OpenApiParam(name = "id", description = "Player id"),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = StatsEntryResponse[].class))
	)
	public void playerStats(@NonNull Context ctx) {
		this.authentication.require(ctx);
		List<StatsEntryResponse> entries = this.stats.forUser(Handlers.pathUuid(ctx, "id")).stream()
			.map(StatsEntryResponse::of)
			.toList();
		ctx.json(entries);
	}
	
	@OpenApi(
		summary = "Upload local single-player history",
		description = "Called once on the offline-to-online transition. Local daily streaks are NOT merged; server "
			+ "streaks start fresh.",
		operationId = "syncStats",
		path = ApiVersion.PATH_PREFIX + "/stats/sync",
		methods = HttpMethod.POST,
		tags = "Stats",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = StatsSyncRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = StatsSyncResponse.class))
	)
	public void sync(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		StatsSyncRequest request = ctx.bodyAsClass(StatsSyncRequest.class);
		
		int merged = this.stats.sync(actor, request.parseEntries());
		ctx.json(new StatsSyncResponse(merged));
	}
}
