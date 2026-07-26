package net.luis.sudoku.domain;

import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import org.jspecify.annotations.NonNull;

/**
 * The signature algorithm a device's keypair uses.
 * <p>
 * Recorded per device, because devices need not agree (server-spec 5): Android uses ECDSA P-256, since
 * hardware-backed Keystore Ed25519 needs API 34 and the app targets minSdk 33.
 */
public enum KeyAlgorithm {
	
	ED25519("Ed25519", "Ed25519"),
	ECDSA_P256("EC", "SHA256withECDSA");
	
	private final String keyFactoryAlgorithm;
	private final String signatureAlgorithm;
	
	KeyAlgorithm(@NonNull String keyFactoryAlgorithm, @NonNull String signatureAlgorithm) {
		this.keyFactoryAlgorithm = keyFactoryAlgorithm;
		this.signatureAlgorithm = signatureAlgorithm;
	}
	
	public static @NonNull KeyAlgorithm of(@NonNull String name) {
		try {
			return valueOf(name.trim().toUpperCase().replace('-', '_'));
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.BAD_REQUEST, "Unsupported key algorithm: " + name);
		}
	}
	
	/**
	 * @return the {@link java.security.KeyFactory} algorithm that decodes an X.509 public key of this type
	 */
	public @NonNull String keyFactoryAlgorithm() {
		return this.keyFactoryAlgorithm;
	}
	
	/**
	 * @return the {@link java.security.Signature} algorithm that verifies a signature of this type
	 */
	public @NonNull String signatureAlgorithm() {
		return this.signatureAlgorithm;
	}
}
