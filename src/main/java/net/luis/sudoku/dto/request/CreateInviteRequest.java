package net.luis.sudoku.dto.request;

import net.luis.sudoku.error.ApiException;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Body of {@code POST /api/v1/invites}. The body itself is optional; an absent expiry means the invite
 * never expires.
 *
 * @param expiresAt optional ISO-8601 instant
 */
public record CreateInviteRequest(@Nullable String expiresAt) {
	
	public @Nullable Instant parseExpiresAt() {
		if (this.expiresAt == null || this.expiresAt.isBlank()) {
			return null;
		}
		
		try {
			return Instant.parse(this.expiresAt);
		} catch (DateTimeParseException e) {
			throw ApiException.badRequest("Field expiresAt must be an ISO-8601 instant, e.g. 2026-08-01T12:00:00Z");
		}
	}
}
