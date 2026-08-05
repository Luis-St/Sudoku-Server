package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.ApiVersion;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.dto.request.*;
import net.luis.sudoku.dto.response.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.match.MatchService;
import net.luis.sudoku.presence.PresenceService;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Match lifecycle over REST (server-spec 10.1). Gameplay itself runs over the WebSocket.
 */
public class MatchHandler {
	
	private final Authentication authentication;
	private final MatchService matches;
	private final PresenceService presence;
	
	public MatchHandler(@NonNull Authentication authentication, @NonNull MatchService matches, @NonNull PresenceService presence) {
		this.authentication = authentication;
		this.matches = matches;
		this.presence = presence;
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
			settings.hintsEnabledOrDefault(),
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
		summary = "The match this player is currently in",
		description = "The running match the caller is a participant of, or 204 when there is none. A client asks this "
			+ "on startup: killing the app closes the socket but leaves the player in the match for the length of the "
			+ "reconnect grace, and nothing on the device survives the process to say which match that was.",
		operationId = "activeMatch",
		path = ApiVersion.PATH_PREFIX + "/matches/active",
		methods = HttpMethod.GET,
		tags = "Matches",
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = MatchResponse.class)),
			@OpenApiResponse(status = "204", description = "Not in a match")
		}
	)
	public void active(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		Match match = this.matches.activeMatch(actor);
		if (match == null) {
			ctx.status(204);
			return;
		}
		ctx.json(MatchResponse.of(match, this.matches.participants(match.id())));
	}

	@OpenApi(
		summary = "Leave a running match",
		description = "Answering \"no\" to rejoining. Ends the match immediately rather than holding the other players "
			+ "at a paused board for the rest of a reconnect grace the player has already decided not to use, and "
			+ "settles stakes exactly as an in-match RESIGN does. Idempotent: a match that has already ended answers 204.",
		operationId = "resignMatch",
		path = ApiVersion.PATH_PREFIX + "/matches/{id}/resign",
		methods = HttpMethod.POST,
		tags = "Matches",
		pathParams = @OpenApiParam(name = "id", description = "Match id"),
		responses = {
			@OpenApiResponse(status = "204"),
			@OpenApiResponse(status = "403", description = "FORBIDDEN", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void resign(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		this.matches.resign(actor, Handlers.pathUuid(ctx, "id"));
		ctx.status(204);
	}

	@OpenApi(
		summary = "Cancel a match",
		description = "The creator calls off a match nobody joined - the lobby's cancel button. Refused with CONFLICT "
			+ "once the match is running, where leaving is resigning rather than cancelling. Idempotent: a match that "
			+ "has already ended still answers 204.",
		operationId = "cancelMatch",
		path = ApiVersion.PATH_PREFIX + "/matches/{id}",
		methods = HttpMethod.DELETE,
		tags = "Matches",
		pathParams = @OpenApiParam(name = "id", description = "Match id"),
		responses = {
			@OpenApiResponse(status = "204"),
			@OpenApiResponse(status = "403", description = "FORBIDDEN", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "409", description = "CONFLICT", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void cancel(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		this.matches.cancel(actor, Handlers.pathUuid(ctx, "id"));
		ctx.status(204);
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
	
	@OpenApi(
		summary = "Ask a specific player to join a match",
		description = "Stored for the target to pick up on their next presence heartbeat, so it reaches them within a "
			+ "heartbeat interval. Only a player with a fresh heartbeat may be asked - an offline one gets "
			+ "PLAYER_OFFLINE rather than an invitation they would find hours later for a match that no longer exists, "
			+ "which is also why the stored request expires within the minute.",
		operationId = "requestMatch",
		path = ApiVersion.PATH_PREFIX + "/matches/{id}/request",
		methods = HttpMethod.POST,
		tags = "Matches",
		pathParams = @OpenApiParam(name = "id", description = "Match id"),
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = MatchRequestRequest.class)),
		responses = {
			@OpenApiResponse(status = "204"),
			@OpenApiResponse(status = "409", description = "PLAYER_OFFLINE", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void request(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		UUID matchId = Handlers.pathUuid(ctx, "id");
		MatchRequestRequest request = ctx.bodyAsClass(MatchRequestRequest.class);
		UUID targetId = Handlers.uuid(Requests.require(request.userId(), "userId"), "userId");
		
		Match match = this.matches.get(matchId);
		if (!this.matches.isParticipant(matchId, actor.userId())) {
			throw ApiException.forbidden("Only a participant may invite others");
		}
		if (targetId.equals(actor.userId())) {
			throw ApiException.badRequest("Cannot request a match against yourself");
		}
		
		// Everything the invitation shows - mode, stake, token, who asked - is read back off the match and the
		// requester when it is served, so nothing about it is copied here.
		this.presence.requestMatch(targetId, match, actor.userId());
		ctx.status(204);
	}
}
