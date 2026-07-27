package net.luis.sudoku.auth;

import net.luis.sudoku.domain.KeyAlgorithm;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * Verifies a device's signature over a challenge nonce.
 * <p>
 * Both algorithms are supported because devices record their own (server-spec 5): Ed25519 is built into
 * the JDK since 15, and ECDSA P-256 is what Android devices use, since a hardware-backed Keystore
 * Ed25519 key needs API 34 while the app targets minSdk 33.
 */
public final class SignatureVerifier {
	
	private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);
	
	/**
	 * P-256 has a 256-bit field order. Checking the curve matters because {@code SHA256withECDSA} would
	 * happily verify against a weaker curve the client chose instead.
	 */
	private static boolean isP256(@NonNull PublicKey key) {
		if (key instanceof ECPublicKey ec) {
			return ec.getParams().getOrder().bitLength() == 256;
		}
		return false;
	}
	
	/**
	 * Checks {@code signature} against {@code nonce} for the given key.
	 * <p>
	 * Returns false rather than throwing on malformed input: a key that no longer parses, or a
	 * structurally invalid signature, is a failed authentication attempt and must be indistinguishable
	 * from a wrong one.
	 */
	public boolean verify(@NonNull KeyAlgorithm algorithm, byte @NonNull [] publicKey, byte @NonNull [] nonce, byte @NonNull [] signature) {
		try {
			PublicKey key = KeyFactory.getInstance(algorithm.keyFactoryAlgorithm()).generatePublic(new X509EncodedKeySpec(publicKey));
			
			Signature verifier = Signature.getInstance(algorithm.signatureAlgorithm());
			verifier.initVerify(key);
			verifier.update(nonce);
			return verifier.verify(signature);
		} catch (GeneralSecurityException | RuntimeException e) {
			log.debug("Signature verification failed for {}: {}", algorithm, e.getMessage());
			return false;
		}
	}
	
	/**
	 * Checks that a public key parses as the algorithm it claims to be, so a device cannot register a
	 * key it could never sign with.
	 *
	 * @throws ApiException with {@link ErrorCode#BAD_REQUEST} if it does not parse
	 */
	public void requireParsable(@NonNull KeyAlgorithm algorithm, byte @NonNull [] publicKey) {
		try {
			PublicKey key = KeyFactory.getInstance(algorithm.keyFactoryAlgorithm()).generatePublic(new X509EncodedKeySpec(publicKey));
			if (algorithm == KeyAlgorithm.ECDSA_P256 && !isP256(key)) {
				throw new ApiException(ErrorCode.BAD_REQUEST, "Key is an EC key but not on the P-256 curve");
			}
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new ApiException(ErrorCode.BAD_REQUEST, "Public key is not a valid " + algorithm + " key");
		}
	}
}
