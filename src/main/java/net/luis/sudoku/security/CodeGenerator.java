package net.luis.sudoku.security;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
	private static final int MATCH_CODE_LENGTH = 8;
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
		return group(this.symbols(LINK_CODE_LENGTH));
	}
	
	/**
	 * The code that gets somebody into a match.
	 * <p>
	 * This used to be a {@link #sessionToken()}, and it was the wrong secret for the job: joining also needs
	 * the match id, so a player had to be handed a 43-character token <i>and</i> a UUID, and the lobby had no
	 * choice but to print both of them. The code is the whole invitation now - it resolves to the match on its
	 * own - so there is one value on the screen and one field to type it into.
	 * <p>
	 * Short enough to read out loud, which is the point, so its brute-force resistance is the usual one for a
	 * code this size (server-spec 12): attempt limiting, plus the fact that a code only opens a lobby nobody
	 * has joined yet and stops meaning anything the moment the match starts.
	 *
	 * @return an 8-symbol match code, formatted in two groups of four
	 */
	public @NonNull String matchCode() {
		return group(this.symbols(MATCH_CODE_LENGTH));
	}

	/**
	 * Brings a typed match code to the exact form {@link #matchCode()} stores, so a lookup is an equality
	 * check rather than a fuzzy match: uppercase, regrouped, and rejected outright if it is not eight symbols
	 * of the alphabet.
	 * <p>
	 * Rejecting here rather than at the database is what keeps a mistyped code from being answered any
	 * differently than a code for a match that has already filled up - both are simply "no such match".
	 *
	 * @return the canonical {@code XXXX-XXXX} form, or null if this cannot be a match code at all
	 */
	public static @Nullable String canonicalMatchCode(@NonNull String code) {
		String raw = normalize(code);
		if (raw.length() != MATCH_CODE_LENGTH) {
			return null;
		}
		for (char c : raw.toCharArray()) {
			if (new String(ALPHABET).indexOf(c) < 0) {
				return null;
			}
		}
		return group(raw);
	}

	/**
	 * @return an 8-symbol account-recovery code, the same shape as a link code since it is redeemed the
	 *   same way - by hand, on a fresh device with no session
	 */
	public @NonNull String recoveryCode() {
		return group(this.symbols(RECOVERY_CODE_LENGTH));
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
	
	/**
	 * Splits an 8-symbol code down the middle. Every code this length is shown grouped, so the grouping lives
	 * in one place rather than in each generator - {@link #normalize(String)} takes the hyphen back out again.
	 */
	private static @NonNull String group(@NonNull String raw) {
		return raw.substring(0, 4) + "-" + raw.substring(4, 8);
	}

	private @NonNull String symbols(int length) {
		StringBuilder builder = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			builder.append(ALPHABET[this.random.nextInt(ALPHABET.length)]);
		}
		return builder.toString();
	}
}
