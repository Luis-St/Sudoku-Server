package net.luis.sudoku.support;

import net.luis.sudoku.domain.KeyAlgorithm;
import org.jspecify.annotations.NonNull;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/**
 * Real keypairs for tests.
 * <p>
 * The server parses and verifies against actual keys, so fabricated byte arrays will not do. Both
 * algorithms are covered because devices record their own and the server must handle either
 * (server-spec 5).
 */
public final class TestKeys {

	private final KeyAlgorithm algorithm;
	private final KeyPair keyPair;

	private TestKeys(@NonNull KeyAlgorithm algorithm, @NonNull KeyPair keyPair) {
		this.algorithm = algorithm;
		this.keyPair = keyPair;
	}

	/**
	 * Generates a fresh keypair. Seeded deterministically per {@code label} so a failing test reproduces
	 * with the same key, which matters when the failure is inside signature verification.
	 */
	public static @NonNull TestKeys generate(@NonNull KeyAlgorithm algorithm, @NonNull String label) {
		try {
			SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
			random.setSeed(label.getBytes(java.nio.charset.StandardCharsets.UTF_8));

			KeyPairGenerator generator;
			if (algorithm == KeyAlgorithm.ED25519) {
				generator = KeyPairGenerator.getInstance("Ed25519");
				generator.initialize(255, random);
			} else {
				generator = KeyPairGenerator.getInstance("EC");
				generator.initialize(new ECGenParameterSpec("secp256r1"), random);
			}
			return new TestKeys(algorithm, generator.generateKeyPair());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Failed to generate a " + algorithm + " keypair", e);
		}
	}

	public static @NonNull TestKeys ed25519(@NonNull String label) {
		return generate(KeyAlgorithm.ED25519, label);
	}

	public static @NonNull TestKeys ecdsa(@NonNull String label) {
		return generate(KeyAlgorithm.ECDSA_P256, label);
	}

	public @NonNull KeyAlgorithm algorithm() {
		return this.algorithm;
	}

	/**
	 * @return the X.509-encoded public key, exactly as a client would send it
	 */
	public byte @NonNull [] publicKey() {
		return this.keyPair.getPublic().getEncoded();
	}

	public byte @NonNull [] sign(byte @NonNull [] message) {
		try {
			Signature signature = Signature.getInstance(this.algorithm.signatureAlgorithm());
			signature.initSign(this.keyPair.getPrivate());
			signature.update(message);
			return signature.sign();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Failed to sign with " + this.algorithm, e);
		}
	}
}
