package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.compat.LegacyDifficulty;
import net.luis.sudoku.daily.DailyService;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.domain.Streak;
import net.luis.sudoku.dto.request.*;
import net.luis.sudoku.dto.response.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.permission.Permission;
import net.luis.sudoku.puzzle.PuzzleFactory;
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
	
	/**
	 * Reduces a stored real tier to the six-tier integer a v1 client can name.
	 *
	 * @param difficultyIndex The stored tier index, 1-15
	 * @return The legacy tier index, 1-6
	 */
	private static int toLegacy(int difficultyIndex) {
		return LegacyDifficulty.toLegacy(PuzzleFactory.singlePlayerDifficultyOfIndex(difficultyIndex));
	}
	
	@OpenApi(
		summary = "Get today's daily puzzle key (v1)",
		description = "Returns the key only; the client generates the grid locally. The first request of a date locks "
			+ "in that day's difficulty tier. The difficulty in the key is the frozen six-tier integer, where 6 is "
			+ "Lisa - use /api/v2/daily for the real 1-15 tier and for the givens.",
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
		summary = "Get today's daily puzzle",
		description = "Returns the grid itself as bit-packed Base64 givens, alongside the key it was generated from, "
			+ "so the client decodes instead of generating. The first request of a date locks in that day's tier. "
			+ "Difficulty is the real tier index 1-15, where 15 is Lisa.",
		operationId = "getDailyV2",
		path = "/api/v2/daily",
		methods = HttpMethod.GET,
		tags = "Daily",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = DailyV2Response.class))
	)
	public void dailyV2(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx, Permission.CAN_PLAY);
		DailyService.Daily today = this.daily.today(actor);
		ctx.json(DailyV2Response.of(today.date(), this.daily.puzzleFor(today.date(), today.key().difficulty())));
	}
	
	@OpenApi(
		summary = "Get your daily preferences (v1)",
		description = "dailyDifficulty is the frozen six-tier integer 1-6, where 6 is Lisa.",
		operationId = "getPreferences",
		path = "/api/v1/preferences",
		methods = HttpMethod.GET,
		tags = "Daily",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PreferencesResponse.class))
	)
	public void preferences(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		ctx.json(new PreferencesResponse(toLegacy(this.daily.preference(actor))));
	}
	
	@OpenApi(
		summary = "Get your daily preferences",
		description = "dailyDifficulty is the real tier index 1-15, where 15 is Lisa.",
		operationId = "getPreferencesV2",
		path = "/api/v2/preferences",
		methods = HttpMethod.GET,
		tags = "Daily",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PreferencesResponse.class))
	)
	public void preferencesV2(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		ctx.json(new PreferencesResponse(this.daily.preference(actor)));
	}
	
	@OpenApi(
		summary = "Set your daily difficulty (v1)",
		description = "Takes effect from the next day only; today's tier was fixed when the day began. dailyDifficulty is "
			+ "the frozen six-tier integer 1-6, where 6 is Lisa.",
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
		
		this.daily.setPreference(actor, LegacyDifficulty.fromLegacy(request.requireDailyDifficulty()).index());
		ctx.json(new PreferencesResponse(toLegacy(this.daily.preference(actor))));
	}
	
	@OpenApi(
		summary = "Set your daily difficulty",
		description = "Takes effect from the next day only; today's tier was fixed when the day began. "
			+ "dailyDifficulty is the real tier index 1-15, where 15 is Lisa.",
		operationId = "setPreferencesV2",
		path = "/api/v2/preferences",
		methods = HttpMethod.PUT,
		tags = "Daily",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = PreferencesRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PreferencesResponse.class))
	)
	public void setPreferencesV2(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		PreferencesRequest request = ctx.bodyAsClass(PreferencesRequest.class);
		
		this.daily.setPreference(actor, request.requireDailyDifficulty());
		ctx.json(new PreferencesResponse(this.daily.preference(actor)));
	}
	
	@OpenApi(
		summary = "Submit a daily result (v1)",
		description = "Verified by replaying the solve order against the regenerated puzzle. A SOLVED result locks the "
			+ "date; FAILED results may be retried all day. A result may be submitted for any date that is not in the "
			+ "future, so a client that finished a daily offline can drain its queue whenever it reconnects; "
			+ "credit is pinned to the date in the body, never the date it arrives. Difficulty is the frozen six-tier "
			+ "integer 1-6, where 6 is Lisa, which is a valid daily tier unlike in a match. solveOrder is an array of "
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
		this.submit(ctx, true);
	}
	
	@OpenApi(
		summary = "Submit a daily result",
		description = "Verified by replaying the solve order against the regenerated puzzle. A SOLVED result locks the "
			+ "date; FAILED results may be retried all day. A result may be submitted for any date that is not in the "
			+ "future, so a client that finished a daily offline can drain its queue whenever it reconnects; "
			+ "credit is pinned to the date in the body, never the date it arrives. Difficulty is the real tier index "
			+ "1-15: Lisa (15) is a valid daily tier, unlike in a match. solveOrder is an array of "
			+ "[cell, digit] PAIRS - "
			+ "[[12,4],[13,9],...] - one per non-given cell, and the replay only passes once every one of them is "
			+ "accounted for, hint-filled cells included. The generated schema below flattens the nested array to "
			+ "\"array of integer\", which is wrong: a client that sends bare cell indices is rejected with 400 and "
			+ "its daily never counts.",
		operationId = "submitDailyResultV2",
		path = "/api/v2/daily/result",
		methods = HttpMethod.POST,
		tags = "Daily",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = DailyResultRequest.class)),
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = DailyResultResponse.class)),
			@OpenApiResponse(status = "409", description = "DAILY_ALREADY_SOLVED or DAILY_DATE_INVALID", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void submitResultV2(@NonNull Context ctx) {
		this.submit(ctx, false);
	}
	
	/**
	 * Records a submitted attempt. Only the difficulty integer differs between the two versions, and it is
	 * translated here so both paths share one body of logic - a second copy is how issuance and verification
	 * drift apart.
	 *
	 * @param ctx The request
	 * @param legacy Whether the body's difficulty is the frozen six-tier integer
	 */
	private void submit(@NonNull Context ctx, boolean legacy) {
		Principal actor = this.authentication.require(ctx, Permission.CAN_PLAY);
		DailyResultRequest request = ctx.bodyAsClass(DailyResultRequest.class);
		int difficulty = request.requireDifficulty();
		
		DailyService.Submit submit = new DailyService.Submit(
			request.parseDate(),
			legacy ? LegacyDifficulty.fromLegacy(difficulty).index() : difficulty,
			request.parseOutcome(),
			request.requireElapsedMs(),
			request.mistakesOrZero(),
			request.hintsUsedOrZero(),
			request.parseSolveOrder()
		);
		
		ctx.json(DailyResultResponse.of(this.daily.submit(actor, submit), this.daily.today()));
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
		ctx.json(DailyResultResponse.StreakResponse.of(merged, this.daily.today()));
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
		ctx.json(DailyResultResponse.StreakResponse.of(this.daily.streak(actor.userId()), this.daily.today()));
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
		ctx.json(DailyResultResponse.StreakResponse.of(this.daily.restoreStreak(actor), this.daily.today()));
	}
	
	@OpenApi(
		summary = "Today's daily leaderboard for one tier (v1)",
		description = "Ranked within a single difficulty tier, on the frozen six-tier scale. Hints used are not exposed.",
		operationId = "dailyLeaderboard",
		path = "/api/v1/daily/leaderboard",
		methods = HttpMethod.GET,
		tags = "Daily",
		queryParams = @OpenApiParam(name = "difficulty", description = "Legacy tier index 1-6, where 6 is Lisa", required = true),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = LeaderboardEntryResponse[].class))
	)
	public void leaderboard(@NonNull Context ctx) {
		this.ranking(ctx, true);
	}
	
	@OpenApi(
		summary = "Today's daily leaderboard for one tier",
		description = "Ranked within a single difficulty tier. Hints used are not exposed.",
		operationId = "dailyLeaderboardV2",
		path = "/api/v2/daily/leaderboard",
		methods = HttpMethod.GET,
		tags = "Daily",
		queryParams = @OpenApiParam(name = "difficulty", description = "Tier index 1-15, where 15 is Lisa", required = true),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = LeaderboardEntryResponse[].class))
	)
	public void leaderboardV2(@NonNull Context ctx) {
		this.ranking(ctx, false);
	}
	
	/**
	 * Serves one tier's ranking, translating the tier when the caller speaks the six-tier scale.
	 *
	 * @param ctx The request
	 * @param legacy Whether the query parameter is the frozen six-tier integer
	 */
	private void ranking(@NonNull Context ctx, boolean legacy) {
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
		
		List<LeaderboardEntryResponse> ranking = this.daily.leaderboard(legacy ? LegacyDifficulty.fromLegacy(difficulty).index() : difficulty).stream()
			.map(LeaderboardEntryResponse::of)
			.toList();
		ctx.json(ranking);
	}
}
