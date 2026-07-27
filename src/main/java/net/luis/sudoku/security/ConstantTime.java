package net.luis.sudoku.security;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * Constant-time comparison for codes and tokens (server-spec 12).
 * <p>
 * {@link String#equals} returns as soon as two characters differ, which leaks how much of a guess was
 * correct through response timing. Anything compared against a secret goes through here instead.
 */
public final class ConstantTime {
	
	private ConstantTime() {}
	
	public static boolean equals(@Nullable String a, @Nullable String b) {
		if (a == null || b == null) {
			return Objects.equals(a, b);
		}
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
	
	public static boolean equals(byte @Nullable [] a, byte @Nullable [] b) {
		if (a == null || b == null) {
			return Arrays.equals(a, b);
		}
		return MessageDigest.isEqual(a, b);
	}
}
