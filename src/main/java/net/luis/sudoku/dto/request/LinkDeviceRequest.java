package net.luis.sudoku.dto.request;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/devices/link} (server-spec 6.4).
 *
 * @param publicKey Base64 X.509-encoded public key of the new device
 * @param keyAlgorithm {@code ED25519} or {@code ECDSA_P256}
 * @param linkCode the code shown on the already-authenticated device
 * @param deviceLabel human-readable label for the new device
 */
public record LinkDeviceRequest(
	@Nullable String publicKey,
	@Nullable String keyAlgorithm,
	@Nullable String linkCode,
	@Nullable String deviceLabel
) {
	
	public byte @NonNull [] decodePublicKey() {
		return Requests.decodeBase64(this.publicKey, "publicKey");
	}
	
	public @NonNull String requireKeyAlgorithm() {
		return Requests.require(this.keyAlgorithm, "keyAlgorithm");
	}
	
	public @NonNull String requireLinkCode() {
		return Requests.require(this.linkCode, "linkCode");
	}
	
	public @NonNull String deviceLabelOrDefault() {
		return this.deviceLabel == null || this.deviceLabel.isBlank() ? "Unnamed device" : this.deviceLabel;
	}
}
