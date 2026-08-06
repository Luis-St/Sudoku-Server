package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.domain.KeyAlgorithm;
import net.luis.sudoku.dto.request.RegisterRequest;
import net.luis.sudoku.dto.response.ErrorResponse;
import net.luis.sudoku.dto.response.SessionResponse;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.security.ClientIp;
import net.luis.sudoku.security.RateLimiter;
import org.jspecify.annotations.NonNull;

/**
 * {@code POST /api/v1/register} - redeem an invite and create a user with their first device.
 */
public class RegisterHandler {
	
	private final RegistrationService registrations;
	private final RateLimiter rateLimiter;
	private final boolean trustProxy;
	
	public RegisterHandler(@NonNull RegistrationService registrations, @NonNull RateLimiter rateLimiter, boolean trustProxy) {
		this.registrations = registrations;
		this.rateLimiter = rateLimiter;
		this.trustProxy = trustProxy;
	}
	
	@OpenApi(
		summary = "Register with an invite code",
		description = "Creates a user and their first device. The bootstrap invite grants ADMIN, but only while no "
			+ "non-revoked admin exists.",
		operationId = "register",
		path = "/api/v1/register",
		methods = HttpMethod.POST,
		tags = "Auth",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = RegisterRequest.class)),
		responses = {
			@OpenApiResponse(status = "201", content = @OpenApiContent(from = SessionResponse.class)),
			@OpenApiResponse(status = "403", description = "INVITE_INVALID or ADMIN_EXISTS",
				content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "409", description = "NAME_TAKEN or KEY_TAKEN",
				content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void register(@NonNull Context ctx) {
		this.rateLimiter.check(RateLimiter.Bucket.REGISTER, ClientIp.of(ctx, this.trustProxy));
		
		RegisterRequest request = ctx.bodyAsClass(RegisterRequest.class);
		byte[] publicKey = request.decodePublicKey();
		
		RegistrationService.Registered registered = this.registrations.register(
			request.requireInviteCode(),
			request.requireDisplayName(),
			publicKey,
			KeyAlgorithm.of(request.requireKeyAlgorithm()),
			request.deviceLabelOrDefault()
		);
		
		ctx.status(201);
		ctx.json(SessionResponse.of(registered.session(), registered.user()));
	}
}
