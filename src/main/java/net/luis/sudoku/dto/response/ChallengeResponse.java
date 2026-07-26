package net.luis.sudoku.dto.response;

import net.luis.sudoku.auth.ChallengeService.Challenge;
import org.jspecify.annotations.NonNull;

import java.util.Base64;

/**
 * Response to {@code POST /api/v1/auth/challenge}.
 *
 * @param nonce Base64 bytes the device must sign, exactly as issued
 * @param expiresAt ISO-8601 instant after which the nonce is refused
 */
public record ChallengeResponse(@NonNull String nonce, @NonNull String expiresAt) {
	
	public static @NonNull ChallengeResponse of(@NonNull Challenge challenge) {
		return new ChallengeResponse(
			Base64.getEncoder().encodeToString(challenge.nonce()),
			challenge.expiresAt().toString()
		);
	}
}
