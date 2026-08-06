package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.currency.CurrencyService;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.dto.request.CurrencySyncRequest;
import net.luis.sudoku.dto.response.CurrencyResponse;
import org.jspecify.annotations.NonNull;

/**
 * The Rhubarb balance (server-spec 9a).
 */
public class CurrencyHandler {
	
	private final Authentication authentication;
	private final CurrencyService currency;
	
	public CurrencyHandler(@NonNull Authentication authentication, @NonNull CurrencyService currency) {
		this.authentication = authentication;
		this.currency = currency;
	}
	
	@OpenApi(
		summary = "Your Rhubarb balance",
		operationId = "getCurrency",
		path = "/api/v1/currency",
		methods = HttpMethod.GET,
		tags = "Currency",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = CurrencyResponse.class))
	)
	public void balance(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		ctx.json(new CurrencyResponse(this.currency.balance(actor.userId())));
	}
	
	@OpenApi(
		summary = "Reconcile a locally accumulated balance",
		description = "Called on connect. The reported balance is plausibility-checked and may be clamped SILENTLY - "
			+ "the response simply carries the reconciled value, with no indication that it was adjusted.",
		operationId = "syncCurrency",
		path = "/api/v1/currency/sync",
		methods = HttpMethod.POST,
		tags = "Currency",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = CurrencySyncRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = CurrencyResponse.class))
	)
	public void sync(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		CurrencySyncRequest request = ctx.bodyAsClass(CurrencySyncRequest.class);
		
		long reconciled = this.currency.sync(actor.userId(), request.requireReportedBalance(), request.gamesPlayedOrZero());
		ctx.json(new CurrencyResponse(reconciled));
	}
}
