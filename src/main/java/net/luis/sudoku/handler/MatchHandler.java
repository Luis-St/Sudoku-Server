package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.ApiVersion;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.dto.request.CreateMatchRequest;
import net.luis.sudoku.dto.request.JoinMatchRequest;
import net.luis.sudoku.dto.response.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.match.MatchService;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Match lifecycle over REST (server-spec 10.1). Gameplay itself runs over the WebSocket.
 */
public class MatchHandler {
	
	private final Authentication authentication;
	private final MatchService matches;
	
	public MatchHandler(@NonNull Authentication authentication, @NonNull MatchService matches) {
		this.authentication = authentication;
		this.matches = matches;
	}
	
	@OpenApi(
		summary = "Create a match",
		description = "The creator chooses the configuration and settings. Difficulty LISA is rejected for every mode.",
		operationId = "createMatch",
		path = ApiVersion.PATH_PREFIX + "/matches",
		methods = HttpMethod.POST,
		tags = "Matches",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = CreateMatchRequest.class)),
		responses = {
			@OpenApiResponse(status = "201", content = @OpenApiContent(from = CreatedMatchResponse.class)),
			@OpenApiResponse(status = "400", description = "LISA_NOT_ALLOWED",
				content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void create(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		CreateMatchRequest request = ctx.bodyAsClass(CreateMatchRequest.class);
		CreateMatchRequest.Config config = request.requireConfig();
		CreateMatchRequest.Settings settings = request.settingsOrDefault();
		
		MatchService.Created created = this.matches.create(
			actor,
			request.requireMode(),
			config.requireSize(),
			config.requireVariant(),
			config.requireDifficulty(),
			settings.livesEnabledOrDefault(),
			settings.stakeOrZero()
		);
		
		ctx.status(201);
		ctx.json(new CreatedMatchResponse(created.match().id().toString(), created.inviteToken()));
	}
	
	@OpenApi(
		summary = "Join a match",
		operationId = "joinMatch",
		path = ApiVersion.PATH_PREFIX + "/matches/{id}/join",
		methods = HttpMethod.POST,
		tags = "Matches",
		pathParams = @OpenApiParam(name = "id", description = "Match id"),
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = JoinMatchRequest.class)),
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = MatchResponse.class)),
			@OpenApiResponse(status = "409", description = "MATCH_FULL or INSUFFICIENT_BALANCE",
				content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void join(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		UUID matchId = Handlers.pathUuid(ctx, "id");
		JoinMatchRequest request = ctx.body().isBlank()
			? new JoinMatchRequest(null)
			: ctx.bodyAsClass(JoinMatchRequest.class);
		
		Match match = this.matches.join(actor, matchId, request.inviteToken());
		ctx.json(MatchResponse.of(match, this.matches.participants(matchId)));
	}
	
	@OpenApi(
		summary = "Get a match",
		operationId = "getMatch",
		path = ApiVersion.PATH_PREFIX + "/matches/{id}",
		methods = HttpMethod.GET,
		tags = "Matches",
		pathParams = @OpenApiParam(name = "id", description = "Match id"),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = MatchResponse.class))
	)
	public void get(@NonNull Context ctx) {
		this.authentication.require(ctx);
		UUID matchId = Handlers.pathUuid(ctx, "id");
		ctx.json(MatchResponse.of(this.matches.get(matchId), this.matches.participants(matchId)));
	}
	
	@OpenApi(
		summary = "Invite a player to a match",
		description = "Returns the invite token to pass on. Only a participant may invite.",
		operationId = "inviteToMatch",
		path = ApiVersion.PATH_PREFIX + "/matches/{id}/invite",
		methods = HttpMethod.POST,
		tags = "Matches",
		pathParams = @OpenApiParam(name = "id", description = "Match id"),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = CreatedMatchResponse.class))
	)
	public void invite(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		UUID matchId = Handlers.pathUuid(ctx, "id");
		
		Match match = this.matches.get(matchId);
		if (!this.matches.isParticipant(matchId, actor.userId())) {
			throw ApiException.forbidden("Only a participant may invite others");
		}
		ctx.json(new CreatedMatchResponse(match.id().toString(), match.inviteToken()));
	}
}
