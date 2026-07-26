package net.luis.sudoku.security;

import io.javalin.http.Context;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the real client address behind the reverse proxy (server-spec 4, 12).
 * <p>
 * The server always speaks plain HTTP behind a TLS-terminating proxy, so the socket peer is the proxy,
 * not the player. Using it directly would collapse every per-IP rate-limit bucket into one shared
 * counter: a single abusive client would lock out everyone, and the per-IP protection would mean
 * nothing.
 * <p>
 * <strong>Header precedence, and why.</strong> {@code X-Real-IP} is preferred because nginx
 * <em>overwrites</em> it ({@code proxy_set_header X-Real-IP $remote_addr}), so a client cannot forge it.
 * {@code X-Forwarded-For} is the fallback, and the <strong>last</strong> entry is used rather than the
 * first: nginx's {@code $proxy_add_x_forwarded_for} <em>appends</em> the real peer to whatever the
 * client sent, so a spoofed {@code X-Forwarded-For: 1.2.3.4} becomes {@code 1.2.3.4, <real client>} and
 * only the final entry is trustworthy. Taking the first, as is commonly done, would hand every attacker
 * a free rate-limit bypass.
 */
public final class ClientIp {
	
	private static final String REAL_IP = "X-Real-IP";
	private static final String FORWARDED_FOR = "X-Forwarded-For";
	
	private ClientIp() {}
	
	/**
	 * @param ctx the request
	 * @param trustProxy whether proxy headers may be believed; false when the server is directly
	 *   reachable, where any such header is client-controlled and must be ignored
	 * @return the address to attribute this request to
	 */
	public static @NonNull String of(@NonNull Context ctx, boolean trustProxy) {
		return resolve(ctx.header(REAL_IP), ctx.header(FORWARDED_FOR), ctx.ip(), trustProxy);
	}
	
	/**
	 * The resolution itself, free of any web framework so it can be tested directly.
	 *
	 * @param realIp the {@code X-Real-IP} header, or null
	 * @param forwardedFor the {@code X-Forwarded-For} header, or null
	 * @param peer the socket peer address, used when no header is usable
	 * @param trustProxy whether the headers may be believed at all
	 */
	static @NonNull String resolve(@Nullable String realIp, @Nullable String forwardedFor, @NonNull String peer,
	                               boolean trustProxy) {
		if (!trustProxy) {
			return peer;
		}
		
		String real = trim(realIp);
		if (real != null) {
			return real;
		}
		
		String forwarded = trim(forwardedFor);
		if (forwarded != null) {
			int lastComma = forwarded.lastIndexOf(',');
			String last = trim(lastComma < 0 ? forwarded : forwarded.substring(lastComma + 1));
			if (last != null) {
				return last;
			}
		}
		return peer;
	}
	
	private static @Nullable String trim(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
