package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.dto.request.GameResultsRequest;
import net.luis.sudoku.dto.request.StatsSyncRequest;
import net.luis.sudoku.dto.response.*;
import net.luis.sudoku.permission.Permission;
import net.luis.sudoku.presence.PresenceService;
import net.luis.sudoku.stats.StatsService;
import org.jspecify.annotations.NonNull;

import java.util.*;

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
		path = "/api/v1/players",
		methods = HttpMethod.GET,
		tags = "Stats",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PlayerResponse[].class))
	)
	public void players(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		// One read of the whole online set, not one per player: this list is polled while the friends screen is
		// open, and asking per row would turn a fixed two queries into one per registered player every time.
		Set<UUID> online = this.presence.onlineUsers();
		// Kicked players are visible to whoever could kick them, and to nobody else - reinstating (spec 7.2)
		// has to start from a list the removed player is actually in.
		List<PlayerResponse> players = this.stats.players(actor.has(Permission.CAN_KICK)).stream()
			.map(summary -> PlayerResponse.of(summary, online.contains(summary.id())))
			.toList();
		ctx.json(players);
	}
	
	@OpenApi(
		summary = "A player's aggregate statistics",
		description = "Grouped by difficulty tier, because solve times are only comparable within a tier.",
		operationId = "playerStats",
		path = "/api/v1/players/{id}/stats",
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
		summary = "Upload finished single-player games",
		description = "Called as games are played, and on reconnection for whatever the client queued while offline. "
			+ "Safe to repeat: a game already recorded is skipped, not counted twice.",
		operationId = "recordGames",
		path = "/api/v1/stats/games",
		methods = HttpMethod.POST,
		tags = "Stats",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = GameResultsRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = GameResultsResponse.class))
	)
	public void recordGames(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		GameResultsRequest request = ctx.bodyAsClass(GameResultsRequest.class);
		
		int recorded = this.stats.recordGames(actor, request.parseGames());
		ctx.json(new GameResultsResponse(recorded));
	}
	
	@OpenApi(
		summary = "Upload local single-player history",
		description = "Called once on the offline-to-online transition. Local daily streaks are NOT merged; server "
			+ "streaks start fresh.",
		operationId = "syncStats",
		path = "/api/v1/stats/sync",
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
