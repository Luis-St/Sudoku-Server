package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.ApiVersion;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.domain.KeyAlgorithm;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.dto.request.*;
import net.luis.sudoku.dto.response.ErrorResponse;
import net.luis.sudoku.dto.response.SessionResponse;
import net.luis.sudoku.recovery.RecoveryService;
import net.luis.sudoku.security.ClientIp;
import net.luis.sudoku.security.RateLimiter;
import org.jspecify.annotations.NonNull;

/**
 * Email verification and account recovery (email-based, no live session required to redeem).
 */
public class RecoveryHandler {
	
	private final Authentication authentication;
	private final RecoveryService recovery;
	private final RateLimiter rateLimiter;
	private final boolean trustProxy;
	
	public RecoveryHandler(@NonNull Authentication authentication, @NonNull RecoveryService recovery, @NonNull RateLimiter rateLimiter, boolean trustProxy) {
		this.authentication = authentication;
		this.recovery = recovery;
		this.rateLimiter = rateLimiter;
		this.trustProxy = trustProxy;
	}
	
	@OpenApi(
		summary = "Request email verification",
		description = "Authenticated. Emails a 6-digit code, superseding any outstanding request.",
		operationId = "requestEmailVerification",
		path = ApiVersion.PATH_PREFIX + "/users/me/email",
		methods = HttpMethod.POST,
		tags = "Recovery",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = EmailRequest.class)),
		responses = {
			@OpenApiResponse(status = "204"),
			@OpenApiResponse(status = "409", description = "EMAIL_TAKEN", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "503", description = "MAIL_NOT_CONFIGURED", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void requestEmailVerification(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		this.rateLimiter.check(RateLimiter.Bucket.EMAIL_VERIFY_REQUEST, ClientIp.of(ctx, this.trustProxy));
		
		EmailRequest request = ctx.bodyAsClass(EmailRequest.class);
		this.recovery.requestEmailVerification(actor, request.requireEmail());
		ctx.status(204);
	}
	
	@OpenApi(
		summary = "Confirm email verification",
		operationId = "confirmEmailVerification",
		path = ApiVersion.PATH_PREFIX + "/users/me/email/verify",
		methods = HttpMethod.POST,
		tags = "Recovery",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = EmailVerifyRequest.class)),
		responses = {
			@OpenApiResponse(status = "204"),
			@OpenApiResponse(status = "403", description = "EMAIL_VERIFICATION_INVALID", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void confirmEmail(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		EmailVerifyRequest request = ctx.bodyAsClass(EmailVerifyRequest.class);
		
		this.recovery.confirmEmail(actor, request.requireCode());
		ctx.status(204);
	}
	
	@OpenApi(
		summary = "Request account recovery",
		description = "Unauthenticated. Always returns 204, whether or not the address matched a verified account, "
			+ "so the endpoint cannot be used to enumerate accounts.",
		operationId = "requestRecovery",
		path = ApiVersion.PATH_PREFIX + "/auth/recovery/request",
		methods = HttpMethod.POST,
		tags = "Recovery",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = RecoveryRequestRequest.class)),
		responses = @OpenApiResponse(status = "204")
	)
	public void requestRecovery(@NonNull Context ctx) {
		this.rateLimiter.check(RateLimiter.Bucket.RECOVERY_REQUEST, ClientIp.of(ctx, this.trustProxy));
		
		RecoveryRequestRequest request = ctx.bodyAsClass(RecoveryRequestRequest.class);
		this.recovery.requestRecovery(request.requireEmail());
		ctx.status(204);
	}
	
	@OpenApi(
		summary = "Redeem an account recovery code",
		description = "Unauthenticated - the new device has no session yet. Hands the whole account over to this device.",
		operationId = "redeemRecovery",
		path = ApiVersion.PATH_PREFIX + "/auth/recovery/redeem",
		methods = HttpMethod.POST,
		tags = "Recovery",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = RecoveryRedeemRequest.class)),
		responses = {
			@OpenApiResponse(status = "201", content = @OpenApiContent(from = SessionResponse.class)),
			@OpenApiResponse(status = "403", description = "RECOVERY_CODE_INVALID", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "429", description = "RATE_LIMITED", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void redeemRecovery(@NonNull Context ctx) {
		// A recovery code hands over the whole account, so this is the tightest rate-limit budget in the
		// system (mirrors DeviceHandler.link).
		this.rateLimiter.check(RateLimiter.Bucket.RECOVERY_REDEEM, ClientIp.of(ctx, this.trustProxy));
		
		RecoveryRedeemRequest request = ctx.bodyAsClass(RecoveryRedeemRequest.class);
		RecoveryService.Redeemed redeemed = this.recovery.redeemRecovery(
			request.requireRecoveryCode(),
			request.decodePublicKey(),
			KeyAlgorithm.of(request.requireKeyAlgorithm()),
			request.deviceLabelOrDefault()
		);
		
		ctx.status(201);
		ctx.json(SessionResponse.of(redeemed.session(), redeemed.user()));
	}
}
