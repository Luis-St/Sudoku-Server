package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.daily.DailyService;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.domain.Streak;
import net.luis.sudoku.dto.request.DailyResultRequest;
import net.luis.sudoku.dto.request.PreferencesRequest;
import net.luis.sudoku.dto.request.StreakSyncRequest;
import net.luis.sudoku.dto.response.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.permission.Permission;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Daily puzzle endpoints (server-spec 8).
 */
public class DailyHandler {
	
	private final Authentication authentication;
	private final DailyService daily;
	
	public DailyHandler(@NonNull Authentication authentication, @NonNull DailyService daily) {
		this.authentication = authentication;
		this.daily = daily;
	}
	
	@OpenApi(
		summary = "Get today's daily puzzle key",
		description = "Returns the key only; the client generates the grid locally. The first request of a date locks "
			+ "in that day's difficulty tier.",
		operationId = "getDaily",
		path = "/api/v1/daily",
		methods = HttpMethod.GET,
		tags = "Daily",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = DailyResponse.class))
	)
	public void daily(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx, Permission.CAN_PLAY);
		ctx.json(DailyResponse.of(this.daily.today(actor)));
	}
	
	@OpenApi(
		summary = "Get your daily preferences",
		operationId = "getPreferences",
		path = "/api/v1/preferences",
		methods = HttpMethod.GET,
		tags = "Daily",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PreferencesResponse.class))
	)
	public void preferences(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		ctx.json(new PreferencesResponse(this.daily.preference(actor)));
	}
	
	@OpenApi(
		summary = "Set your daily difficulty",
		description = "Takes effect from the next day only; today's tier was fixed when the day began.",
		operationId = "setPreferences",
		path = "/api/v1/preferences",
		methods = HttpMethod.PUT,
		tags = "Daily",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = PreferencesRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PreferencesResponse.class))
	)
	public void setPreferences(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		PreferencesRequest request = ctx.bodyAsClass(PreferencesRequest.class);
		
		this.daily.setPreference(actor, request.requireDailyDifficulty());
		ctx.json(new PreferencesResponse(this.daily.preference(actor)));
	}
	
	@OpenApi(
		summary = "Submit a daily result",
		description = "Verified by replaying the solve order against the regenerated puzzle. A SOLVED result locks the "
			+ "date; FAILED results may be retried all day. A result may be submitted for any date that is not in the "
			+ "future, so a client that finished a daily offline can drain its queue whenever it reconnects; "
			+ "credit is pinned to the date in the body, never the date it arrives. Difficulty is 1-6: Lisa is a "
			+ "valid daily tier, unlike in a match. solveOrder is an array of "
			+ "[cell, digit] PAIRS - "
			+ "[[12,4],[13,9],...] - one per non-given cell, and the replay only passes once every one of them is "
			+ "accounted for, hint-filled cells included. The generated schema below flattens the nested array to "
			+ "\"array of integer\", which is wrong: a client that sends bare cell indices is rejected with 400 and "
			+ "its daily never counts.",
		operationId = "submitDailyResult",
		path = "/api/v1/daily/result",
		methods = HttpMethod.POST,
		tags = "Daily",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = DailyResultRequest.class)),
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = DailyResultResponse.class)),
			@OpenApiResponse(status = "409", description = "DAILY_ALREADY_SOLVED or DAILY_DATE_INVALID", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void submitResult(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx, Permission.CAN_PLAY);
		DailyResultRequest request = ctx.bodyAsClass(DailyResultRequest.class);
		
		DailyService.Submit submit = new DailyService.Submit(
			request.parseDate(),
			request.requireDifficulty(),
			request.parseOutcome(),
			request.requireElapsedMs(),
			request.mistakesOrZero(),
			request.hintsUsedOrZero(),
			request.parseSolveOrder()
		);
		
		ctx.json(DailyResultResponse.of(this.daily.submit(actor, submit)));
	}
	
	@OpenApi(
		summary = "Publish the streak this device counted",
		description = "For a streak the server never saw earned: a daily solved while it was unreachable advances "
			+ "the device's count, and if that queued submission is later lost the server has no other way to hear "
			+ "about the day. Strictly one-way - a count lower than the stored one is ignored - so it is safe to "
			+ "send on every reconnect and safe to repeat. Restore points are never granted from a claim; only a "
			+ "replay-verified solve earns those. Returns the streak as it stands after the merge.",
		operationId = "syncStreak",
		path = "/api/v1/daily/streak/sync",
		methods = HttpMethod.POST,
		tags = "Daily",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = StreakSyncRequest.class)),
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = DailyResultResponse.StreakResponse.class)),
			@OpenApiResponse(status = "400", description = "BAD_REQUEST for a negative or implausible count", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void syncStreak(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx, Permission.CAN_PLAY);
		StreakSyncRequest request = ctx.bodyAsClass(StreakSyncRequest.class);

		Streak merged = this.daily.syncStreak(actor, request.requireCurrent(), request.parseLastCompletedDate());
		ctx.json(DailyResultResponse.StreakResponse.of(merged));
	}

	@OpenApi(
		summary = "Get your daily streak",
		description = "Current and longest run, and banked restore points.",
		operationId = "getStreak",
		path = "/api/v1/daily/streak",
		methods = HttpMethod.GET,
		tags = "Daily",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = DailyResultResponse.StreakResponse.class))
	)
	public void streak(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		ctx.json(DailyResultResponse.StreakResponse.of(this.daily.streak(actor.userId())));
	}
	
	@OpenApi(
		summary = "Restore a broken streak",
		description = "Spends banked restore points (1 per missed day, 10 Rhubarb per point) to repair a gap, so "
			+ "today's submission extends the streak instead of restarting it.",
		operationId = "restoreStreak",
		path = "/api/v1/daily/streak/restore",
		methods = HttpMethod.POST,
		tags = "Daily",
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = DailyResultResponse.StreakResponse.class)),
			@OpenApiResponse(status = "409", description = "STREAK_RESTORE_NOT_NEEDED, INSUFFICIENT_RESTORE_POINTS or INSUFFICIENT_BALANCE",
				content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void restoreStreak(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		ctx.json(DailyResultResponse.StreakResponse.of(this.daily.restoreStreak(actor)));
	}
	
	@OpenApi(
		summary = "Today's daily leaderboard for one tier",
		description = "Ranked within a single difficulty tier. Hints used are not exposed.",
		operationId = "dailyLeaderboard",
		path = "/api/v1/daily/leaderboard",
		methods = HttpMethod.GET,
		tags = "Daily",
		queryParams = @OpenApiParam(name = "difficulty", description = "Tier index 1-5", required = true),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = LeaderboardEntryResponse[].class))
	)
	public void leaderboard(@NonNull Context ctx) {
		this.authentication.require(ctx);
		String raw = ctx.queryParam("difficulty");
		if (raw == null) {
			throw ApiException.badRequest("Query parameter difficulty is required");
		}
		
		int difficulty;
		try {
			difficulty = Integer.parseInt(raw);
		} catch (NumberFormatException e) {
			throw ApiException.badRequest("Query parameter difficulty must be an integer, got: " + raw);
		}
		
		List<LeaderboardEntryResponse> ranking = this.daily.leaderboard(difficulty).stream()
			.map(LeaderboardEntryResponse::of)
			.toList();
		ctx.json(ranking);
	}
}
