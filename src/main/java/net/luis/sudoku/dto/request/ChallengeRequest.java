package net.luis.sudoku.dto.request;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/auth/challenge}.
 *
 * @param publicKey Base64 X.509-encoded public key of the device requesting a nonce
 */
public record ChallengeRequest(@Nullable String publicKey) {
	
	public byte @NonNull [] decodePublicKey() {
		return Requests.decodeBase64(this.publicKey, "publicKey");
	}
}
