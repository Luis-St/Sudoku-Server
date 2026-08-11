package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.db.schema.Schema.LearnProgressRow;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.dto.request.LearnSyncRequest;
import net.luis.sudoku.dto.response.LearnProgressResponse;
import net.luis.sudoku.learning.LearnService;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * The learn area's progress, shared between an account's devices.
 * <p>
 * Additive: no existing route changed, so no API version moved. The learn area works entirely offline and
 * these two routes only ever make a second device start from what the first one already did.
 */
public class LearnHandler {

	private final Authentication authentication;
	private final LearnService learn;

	public LearnHandler(@NonNull Authentication authentication, @NonNull LearnService learn) {
		this.authentication = authentication;
		this.learn = learn;
	}

	@OpenApi(
		summary = "Your learn area progress",
		description = "Every exercise this account has finished, on any of its devices, plus how many techniques it "
			+ "has mastered. A technique is mastered when all of its exercises were solved USING it, so an exercise "
			+ "finished without the technique counts as done and earns nothing.",
		operationId = "getLearnProgress",
		path = "/api/v1/learn/progress",
		methods = HttpMethod.GET,
		tags = "Learn",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = LearnProgressResponse.class))
	)
	public void progress(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		List<LearnProgressRow> rows = this.learn.progressOf(actor.userId());
		ctx.json(LearnProgressResponse.of(rows, LearnService.masteredCount(rows), 0));
	}

	@OpenApi(
		summary = "Report what this device has finished",
		description = "The server keeps whichever state is FURTHER ALONG, never whichever arrived later: both devices "
			+ "of an account may work offline for as long as they like, so a stale PARTIAL must not land on top of a "
			+ "SOLVED and un-earn an achievement the player has already been shown. The response carries everything "
			+ "the account holds afterwards, which is what the device adopts.",
		operationId = "syncLearnProgress",
		path = "/api/v1/learn/sync",
		methods = HttpMethod.POST,
		tags = "Learn",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = LearnSyncRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = LearnProgressResponse.class))
	)
	public void sync(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		LearnSyncRequest request = ctx.bodyAsClass(LearnSyncRequest.class);

		List<LearnProgressRow> reported = request.requireEntries().stream()
			.map(entry -> this.learn.rowOf(
				actor.userId(), entry.requireTechnique(), entry.requireLevel(), entry.requireSubLevel(), entry.requireState()
			))
			.toList();

		LearnService.Merged merged = this.learn.sync(actor.userId(), reported);
		ctx.json(LearnProgressResponse.of(merged.rows(), LearnService.masteredCount(merged.rows()), merged.accepted()));
	}

	@OpenApi(
		summary = "Clear one technique's training",
		description = "The player asking to start a technique over. It is the one call that may take a solve away, "
			+ "which is why it names a single technique rather than clearing the area.",
		operationId = "resetLearnTechnique",
		path = "/api/v1/learn/{technique}",
		methods = HttpMethod.DELETE,
		tags = "Learn",
		pathParams = @OpenApiParam(name = "technique", description = "The technique's identifier"),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = LearnProgressResponse.class))
	)
	public void reset(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		this.learn.reset(actor.userId(), ctx.pathParam("technique"));

		List<LearnProgressRow> rows = this.learn.progressOf(actor.userId());
		ctx.json(LearnProgressResponse.of(rows, LearnService.masteredCount(rows), 0));
	}
}
