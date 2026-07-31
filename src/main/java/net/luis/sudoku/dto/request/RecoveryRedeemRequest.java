package net.luis.sudoku.dto.request;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/auth/recovery/redeem}. Unauthenticated - the new device has no session
 * yet, mirroring {@code LinkDeviceRequest}.
 *
 * @param recoveryCode the code emailed to the account's verified address
 * @param publicKey Base64 X.509-encoded public key of the new device
 * @param keyAlgorithm {@code ED25519} or {@code ECDSA_P256}
 * @param deviceLabel human-readable label for the new device
 */
public record RecoveryRedeemRequest(
	@Nullable String recoveryCode,
	@Nullable String publicKey,
	@Nullable String keyAlgorithm,
	@Nullable String deviceLabel
) {
	
	public @NonNull String requireRecoveryCode() {
		return Requests.require(this.recoveryCode, "recoveryCode");
	}
	
	public byte @NonNull [] decodePublicKey() {
		return Requests.decodeBase64(this.publicKey, "publicKey");
	}
	
	public @NonNull String requireKeyAlgorithm() {
		return Requests.require(this.keyAlgorithm, "keyAlgorithm");
	}
	
	public @NonNull String deviceLabelOrDefault() {
		return this.deviceLabel == null || this.deviceLabel.isBlank() ? "Unnamed device" : this.deviceLabel;
	}
}
