package net.luis.sudoku.dto.request;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/register} (server-spec 6.3).
 *
 * @param publicKey Base64 X.509-encoded public key
 * @param keyAlgorithm {@code ED25519} or {@code ECDSA_P256}
 * @param inviteCode the invite being redeemed
 * @param displayName the name other players will see
 * @param deviceLabel human-readable label for this device
 */
public record RegisterRequest(
	@Nullable String publicKey,
	@Nullable String keyAlgorithm,
	@Nullable String inviteCode,
	@Nullable String displayName,
	@Nullable String deviceLabel
) {
	
	public byte @NonNull [] decodePublicKey() {
		return Requests.decodeBase64(this.publicKey, "publicKey");
	}
	
	public @NonNull String requireKeyAlgorithm() {
		return Requests.require(this.keyAlgorithm, "keyAlgorithm");
	}
	
	public @NonNull String requireInviteCode() {
		return Requests.require(this.inviteCode, "inviteCode");
	}
	
	public @NonNull String requireDisplayName() {
		return Requests.require(this.displayName, "displayName");
	}
	
	/**
	 * @return the supplied device label, or a neutral default - the label is cosmetic, so a missing one
	 *   is not worth failing the registration over
	 */
	public @NonNull String deviceLabelOrDefault() {
		return this.deviceLabel == null || this.deviceLabel.isBlank() ? "Unnamed device" : this.deviceLabel;
	}
}
