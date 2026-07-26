package net.luis.sudoku.dto.request;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/auth/verify}.
 * <p>
 * The nonce identifies the challenge, so the public key need not be resent - the server already knows
 * which key the nonce was issued to, and trusting a client-supplied key here would let a caller pick
 * which key their signature is checked against.
 *
 * @param nonce Base64 nonce from the challenge response
 * @param signature Base64 signature over the raw nonce bytes
 */
public record VerifyRequest(@Nullable String nonce, @Nullable String signature) {
	
	public byte @NonNull [] decodeNonce() {
		return Requests.decodeBase64(this.nonce, "nonce");
	}
	
	public byte @NonNull [] decodeSignature() {
		return Requests.decodeBase64(this.signature, "signature");
	}
}
