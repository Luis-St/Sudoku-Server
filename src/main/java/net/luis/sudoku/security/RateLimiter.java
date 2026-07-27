package net.luis.sudoku.security;

import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import org.jspecify.annotations.NonNull;

import java.time.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window attempt limiting for the endpoints that guard secrets (server-spec 12).
 * <p>
 * Invite and link codes are short and human-typable, so their brute-force resistance comes from
 * limiting attempts rather than from code length. In-memory is the right scope: match state is already
 * single-JVM (spec 5.1), so there is no second instance for a shared counter to serve.
 * <p>
 * Windows are fixed rather than sliding, which permits a burst of up to twice the limit across a window
 * boundary. That is an acceptable trade for a friends' server, and it costs one counter per key instead
 * of a timestamp list.
 */
public final class RateLimiter {
	
	private final Clock clock;
	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	
	public RateLimiter() {
		this(Clock.systemUTC());
	}
	
	public RateLimiter(@NonNull Clock clock) {
		this.clock = clock;
	}
	
	/**
	 * Records an attempt against {@code bucket} for {@code key}, which is an IP or a public key
	 * fingerprint.
	 *
	 * @throws ApiException with {@link ErrorCode#RATE_LIMITED} once the budget is exhausted
	 */
	public void check(@NonNull Bucket bucket, @NonNull String key) {
		Instant now = this.clock.instant();
		String composite = bucket.name() + '|' + key;
		
		Window window = this.windows.compute(composite, (_, existing) -> {
			if (existing == null || existing.isExpired(now)) {
				return new Window(now.plus(bucket.window()));
			}
			return existing;
		});
		
		if (window.count.incrementAndGet() > bucket.limit()) {
			throw new ApiException(ErrorCode.RATE_LIMITED, "Too many attempts; try again after " + window.expiresAt, Map.of("retryAfterSeconds", Math.max(1, Duration.between(now, window.expiresAt).toSeconds())));
		}
	}
	
	/**
	 * Drops windows that have expired. Called periodically so a long uptime with many distinct client
	 * IPs cannot grow the map without bound.
	 */
	public void sweep() {
		Instant now = this.clock.instant();
		this.windows.values().removeIf(window -> window.isExpired(now));
	}
	
	/**
	 * Clears all counters. Test seam only.
	 */
	void reset() {
		this.windows.clear();
	}
	
	/**
	 * The limited endpoints and their budgets.
	 */
	public enum Bucket {
		
		/** Cheap and unauthenticated, but it reveals whether a key is known, so it is still limited. */
		AUTH_CHALLENGE(30, Duration.ofMinutes(1)),
		/** Signature verification: the expensive half of the handshake. */
		AUTH_VERIFY(30, Duration.ofMinutes(1)),
		/** Guessing an invite code should be hopeless well before the TTL expires. */
		REGISTER(10, Duration.ofMinutes(10)),
		/** Link codes are the shortest secret in the system, so this is the tightest budget. */
		DEVICE_LINK(10, Duration.ofMinutes(10));
		
		private final int limit;
		private final Duration window;
		
		Bucket(int limit, @NonNull Duration window) {
			this.limit = limit;
			this.window = window;
		}
		
		public int limit() {
			return this.limit;
		}
		
		public @NonNull Duration window() {
			return this.window;
		}
	}
	
	private static final class Window {
		
		private final Instant expiresAt;
		private final AtomicInteger count = new AtomicInteger();
		
		private Window(@NonNull Instant expiresAt) {
			this.expiresAt = expiresAt;
		}
		
		private boolean isExpired(@NonNull Instant now) {
			return !this.expiresAt.isAfter(now);
		}
	}
}
