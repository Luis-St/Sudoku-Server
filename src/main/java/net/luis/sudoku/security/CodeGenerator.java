package net.luis.sudoku.security;

import org.jspecify.annotations.NonNull;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates every random secret the server mints: invite codes, link codes, session tokens and nonces.
 * <p>
 * All of them come from {@link SecureRandom}. Invite and link codes use a Crockford-style Base32
 * alphabet with {@code I}, {@code L}, {@code O} and {@code U} removed, so a human retyping a code
 * cannot confuse it with {@code 1} or {@code 0}. Their brute-force resistance comes from attempt
 * limiting plus a short TTL rather than from length (server-spec 12).
 */
public final class CodeGenerator {
	
	/**
	 * Crockford Base32 minus the vowel that makes accidental words: 32 unambiguous symbols.
	 */
	private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
	
	private static final int INVITE_CODE_LENGTH = 12;
	private static final int LINK_CODE_LENGTH = 8;
	private static final int RECOVERY_CODE_LENGTH = 8;
	private static final int EMAIL_VERIFICATION_CODE_DIGITS = 6;
	private static final int SESSION_TOKEN_BYTES = 32;
	
	/** 32 random bytes, per server-spec 6.1. */
	public static final int NONCE_BYTES = 32;
	
	private final SecureRandom random;
	
	public CodeGenerator() {
		this(new SecureRandom());
	}
	
	public CodeGenerator(@NonNull SecureRandom random) {
		this.random = random;
	}
	
	/**
	 * Normalises a human-typed code: uppercase, and with the grouping hyphens and any stray whitespace
	 * removed, so {@code abcd-efgh} and {@code ABCDEFGH} are the same code.
	 */
	public static @NonNull String normalize(@NonNull String code) {
		StringBuilder builder = new StringBuilder(code.length());
		for (char c : code.toCharArray()) {
			if (c != '-' && !Character.isWhitespace(c)) {
				builder.append(Character.toUpperCase(c));
			}
		}
		return builder.toString();
	}
	
	/**
	 * @return a 12-symbol invite code, formatted in groups of four for legibility
	 */
	public @NonNull String inviteCode() {
		String raw = this.symbols(INVITE_CODE_LENGTH);
		return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12);
	}
	
	/**
	 * @return an 8-symbol link code, short because a person types it from one screen onto another
	 */
	public @NonNull String linkCode() {
		String raw = this.symbols(LINK_CODE_LENGTH);
		return raw.substring(0, 4) + "-" + raw.substring(4, 8);
	}
	
	/**
	 * @return an 8-symbol account-recovery code, the same shape as a link code since it is redeemed the
	 *   same way - by hand, on a fresh device with no session
	 */
	public @NonNull String recoveryCode() {
		String raw = this.symbols(RECOVERY_CODE_LENGTH);
		return raw.substring(0, 4) + "-" + raw.substring(4, 8);
	}
	
	/**
	 * @return a 6-digit numeric code, easy to type back from an email client
	 */
	public @NonNull String emailVerificationCode() {
		StringBuilder builder = new StringBuilder(EMAIL_VERIFICATION_CODE_DIGITS);
		for (int i = 0; i < EMAIL_VERIFICATION_CODE_DIGITS; i++) {
			builder.append(this.random.nextInt(10));
		}
		return builder.toString();
	}
	
	/**
	 * @return an opaque 256-bit session token, URL-safe so it can also ride a WebSocket query parameter
	 */
	public @NonNull String sessionToken() {
		byte[] bytes = new byte[SESSION_TOKEN_BYTES];
		this.random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
	
	public byte @NonNull [] nonce() {
		byte[] bytes = new byte[NONCE_BYTES];
		this.random.nextBytes(bytes);
		return bytes;
	}
	
	private @NonNull String symbols(int length) {
		StringBuilder builder = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			builder.append(ALPHABET[this.random.nextInt(ALPHABET.length)]);
		}
		return builder.toString();
	}
}
