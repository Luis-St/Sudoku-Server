package net.luis.sudoku.daily;

import net.luis.sudoku.key.KeyDerivation;
import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Derives the daily puzzle seed (server-spec 8, feature-spec 3.1):
 * <pre>seed = fold64( SHA-256( serverId ‖ "/" ‖ yyyy-MM-dd ) )</pre>
 * <p>
 * Both hash and fold come from shared-core's {@link KeyDerivation} rather than being reimplemented
 * here: the client computes the same seed offline, and two independent implementations of the same
 * formula are two chances to disagree.
 * <p>
 * The difficulty is deliberately <em>not</em> part of the seed. It is part of the {@code PuzzleKey}
 * instead, and shared-core folds the whole key into the generator state, so each tier gets its own
 * unrelated puzzle from the same seed.
 * <p>
 * That {@code serverId} and the date are both publicly derivable means a player can precompute
 * tomorrow's daily. This is an accepted limitation (spec 12), not an oversight - a secret salt would
 * fix it at the cost of offline daily generation.
 */
public final class SeedDerivation {
	
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	
	private SeedDerivation() {}
	
	public static long seedFor(@NonNull String serverId, @NonNull LocalDate date) {
		String input = serverId + "/" + DATE.format(date);
		return KeyDerivation.fold64(KeyDerivation.sha256(input.getBytes(StandardCharsets.UTF_8)));
	}
	
	/**
	 * @return the date as the client formats it, {@code yyyy-MM-dd}
	 */
	public static @NonNull String format(@NonNull LocalDate date) {
		return DATE.format(date);
	}
}
