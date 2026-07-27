package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.ApiVersion;
import net.luis.sudoku.auth.ChallengeService;
import net.luis.sudoku.dto.request.ChallengeRequest;
import net.luis.sudoku.dto.request.VerifyRequest;
import net.luis.sudoku.dto.response.*;
import net.luis.sudoku.security.ClientIp;
import net.luis.sudoku.security.RateLimiter;
import org.jspecify.annotations.NonNull;

/**
 * The challenge-response handshake endpoints (server-spec 6.1).
 */
public class AuthHandler {
	
	private final ChallengeService challenges;
	private final RateLimiter rateLimiter;
	private final boolean trustProxy;
	
	public AuthHandler(@NonNull ChallengeService challenges, @NonNull RateLimiter rateLimiter, boolean trustProxy) {
		this.challenges = challenges;
		this.rateLimiter = rateLimiter;
		this.trustProxy = trustProxy;
	}
	
	/**
	 * @return a short, stable rate-limit key derived from a public key, so the limiter map holds
	 *   fixed-size entries instead of full keys
	 */
	private static @NonNull String fingerprint(byte @NonNull [] publicKey) {
		return Integer.toHexString(java.util.Arrays.hashCode(publicKey));
	}
	
	@OpenApi(
		summary = "Request an authentication challenge",
		description = "Returns a single-use nonce the device must sign. 404 if the key is unknown, in which case the "
			+ "client should register or link a device instead.",
		operationId = "authChallenge",
		path = ApiVersion.PATH_PREFIX + "/auth/challenge",
		methods = HttpMethod.POST,
		tags = "Auth",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = ChallengeRequest.class)),
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = ChallengeResponse.class)),
			@OpenApiResponse(status = "404", description = "UNKNOWN_KEY", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "403", description = "USER_REVOKED", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void challenge(@NonNull Context ctx) {
		this.rateLimiter.check(RateLimiter.Bucket.AUTH_CHALLENGE, ClientIp.of(ctx, this.trustProxy));
		
		ChallengeRequest request = ctx.bodyAsClass(ChallengeRequest.class);
		byte[] publicKey = request.decodePublicKey();
		// A second budget keyed by the public key, so one abusive device cannot exhaust an IP's quota
		// for everyone behind the same NAT, and vice versa (spec 12).
		this.rateLimiter.check(RateLimiter.Bucket.AUTH_CHALLENGE, fingerprint(publicKey));
		
		ctx.json(ChallengeResponse.of(this.challenges.challenge(publicKey)));
	}
	
	@OpenApi(
		summary = "Verify a signed challenge",
		description = "Issues a session token. Any failure - bad signature, expired nonce, reused nonce - returns the "
			+ "same INVALID_SIGNATURE.",
		operationId = "authVerify",
		path = ApiVersion.PATH_PREFIX + "/auth/verify",
		methods = HttpMethod.POST,
		tags = "Auth",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = VerifyRequest.class)),
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = SessionResponse.class)),
			@OpenApiResponse(status = "401", description = "INVALID_SIGNATURE",
				content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void verify(@NonNull Context ctx) {
		this.rateLimiter.check(RateLimiter.Bucket.AUTH_VERIFY, ClientIp.of(ctx, this.trustProxy));
		
		VerifyRequest request = ctx.bodyAsClass(VerifyRequest.class);
		ChallengeService.Authenticated authenticated = this.challenges.verify(request.decodeNonce(), request.decodeSignature());
		
		ctx.json(SessionResponse.of(authenticated.session(), authenticated.user()));
	}
}
