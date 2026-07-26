package net.luis.sudoku.auth;

import io.javalin.http.Context;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.permission.Permission;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Clock;

/**
 * Resolves {@code Authorization: Bearer <token>} into a {@link Principal} on the request context.
 * <p>
 * Deliberately lazy rather than a blanket {@code before} filter: most routes need a principal, but
 * {@code /health}, {@code /server-info}, {@code /register} and the auth handshake must stay
 * unauthenticated. Handlers ask for what they need, so a new public route cannot accidentally inherit
 * an auth requirement, and a new private one cannot accidentally skip it - it simply has no principal
 * to read.
 */
public final class Authentication {
	
	private static final String ATTRIBUTE = "sudoku.principal";
	private static final String BEARER = "Bearer ";
	
	/** WebSocket upgrades cannot set headers, so the token may also ride a query parameter (spec 6.1). */
	public static final String TOKEN_QUERY_PARAM = "token";
	
	private final SessionService sessions;
	private final Clock clock;
	
	public Authentication(@NonNull SessionService sessions, @NonNull Clock clock) {
		this.sessions = sessions;
		this.clock = clock;
	}
	
	static @Nullable String extractToken(@NonNull Context ctx) {
		String header = ctx.header("Authorization");
		if (header != null && header.startsWith(BEARER)) {
			String token = header.substring(BEARER.length()).trim();
			if (!token.isEmpty()) {
				return token;
			}
		}
		String query = ctx.queryParam(TOKEN_QUERY_PARAM);
		return query == null || query.isBlank() ? null : query;
	}
	
	/**
	 * Resolves and caches the caller for this request.
	 *
	 * @throws ApiException {@code UNAUTHORIZED} if there is no usable token
	 */
	public @NonNull Principal require(@NonNull Context ctx) {
		Principal cached = ctx.attribute(ATTRIBUTE);
		if (cached != null) {
			return cached;
		}
		
		String token = extractToken(ctx);
		if (token == null) {
			throw new ApiException(ErrorCode.UNAUTHORIZED, "Missing bearer token");
		}
		
		Principal principal = this.sessions.authenticate(token, this.clock.instant());
		ctx.attribute(ATTRIBUTE, principal);
		return principal;
	}
	
	/**
	 * Resolves the caller and enforces a permission in one step - the shape most handlers want.
	 */
	public @NonNull Principal require(@NonNull Context ctx, @NonNull Permission permission) {
		Principal principal = this.require(ctx);
		principal.require(permission);
		return principal;
	}
	
	/**
	 * @return the caller if the request carries a valid token, otherwise null - for the few endpoints
	 *   whose response differs for an authenticated caller but which do not demand one
	 */
	public @Nullable Principal optional(@NonNull Context ctx) {
		try {
			return this.require(ctx);
		} catch (ApiException e) {
			return null;
		}
	}
}
