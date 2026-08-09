package net.luis.sudoku.compat;

import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.error.ApiException;
import org.jspecify.annotations.NonNull;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Translates between the six-tier difficulty integer a v1 client speaks and the real fifteen-tier
 * {@link Difficulty}.
 * <p>
 * <strong>Why this exists.</strong> The difficulty integer on the wire did not gain values when the scale
 * went from six bands to fifteen - it changed <em>meaning</em>. To a client already in the wild, {@code 6}
 * is Lisa and {@code 7} is nothing at all; to this server {@code 6} is an ordinary mid tier and Lisa is
 * {@code 15}. Serving the new numbers on the old routes would have handed every installed app a Lisa
 * puzzle where it asked for the hardest ordinary tier, and rejected as invalid every value it could not
 * name. That is a contract change, so per {@link net.luis.sudoku.ApiVersion} the routes carrying a
 * difficulty integer are registered again under {@code v2} and the {@code v1} paths keep working - by
 * translating here.
 * <p>
 * <strong>The mapping.</strong> The five numbered legacy tiers are spread evenly across the fourteen
 * numbered real ones, and Lisa is Lisa at either end of the scale:
 * <table border="1">
 *     <caption>legacy to real</caption>
 *     <tr><th>v1</th><td>1</td><td>2</td><td>3</td><td>4</td><td>5</td><td>6</td></tr>
 *     <tr><th>real</th><td>1</td><td>4</td><td>7</td><td>10</td><td>13</td><td>15 (Lisa)</td></tr>
 * </table>
 * Even spacing rather than a "closest by feel" table is deliberate: it makes {@link #toLegacy} the exact
 * inverse of {@link #fromLegacy} on all six anchors, so a v1 client that reads a tier back, stores it and
 * sends it again gets the same puzzle band it started from. A real tier that is not an anchor - one only a
 * v2 client could have chosen - snaps to the nearest anchor on the way out, which is the most honest thing
 * a six-value field can say about it.
 * <p>
 * <strong>This is the only class that knows six tiers ever existed.</strong> Nothing else in the server
 * should carry a legacy difficulty: handlers translate at the edge, and everything behind them - services,
 * repositories, the database, the puzzle key - speaks real {@link Difficulty} exclusively. When v1 is
 * finally retired, deleting this class and its call sites is the whole job.
 *
 * @see net.luis.sudoku.ApiVersion
 */
public final class LegacyDifficulty {
	
	/**
	 * The real tier index each legacy tier {@code 1..5} names, in order. Legacy {@code 6} is Lisa and is
	 * handled separately, because it is the one legacy value whose meaning did not move.
	 */
	private static final int[] ANCHORS = { 1, 4, 7, 10, 13 };
	
	/** The legacy index of Lisa, which is one past the numbered legacy tiers. */
	public static final int LEGACY_LISA = ANCHORS.length + 1;
	
	private LegacyDifficulty() {}
	
	/**
	 * Resolves a v1 difficulty integer to the tier it names.
	 *
	 * @param legacyIndex The tier index a v1 client sent, {@code 1}-{@link #LEGACY_LISA}
	 * @return The real tier
	 * @throws ApiException {@code BAD_REQUEST} if the index is outside the six values v1 ever had
	 */
	public static @NonNull Difficulty fromLegacy(int legacyIndex) {
		if (legacyIndex == LEGACY_LISA) {
			return Difficulty.LISA;
		}
		if (legacyIndex < 1 || legacyIndex > ANCHORS.length) {
			throw ApiException.badRequest("difficulty must be between 1 and " + LEGACY_LISA + ", got: " + legacyIndex);
		}
		return Difficulty.ofIndex(ANCHORS[legacyIndex - 1]);
	}
	
	/**
	 * The legacy tiers from Lisa downwards, which is the order a stored column must be rewritten in.
	 * <p>
	 * <strong>Descending is load bearing.</strong> Rewriting a column in place means each legacy value is
	 * replaced by its real one with a separate statement, and ascending order would double apply: legacy
	 * {@code 2} becomes {@code 4}, and the statement for legacy {@code 4} would then find those same rows
	 * and move them again to {@code 10}. Descending, every value a statement writes is above every value a
	 * later statement looks for, so no row is touched twice. {@code SchemaMigration}'s rescale is the only
	 * caller, and it lives here rather than there so the order travels with the mapping it depends on.
	 *
	 * @return The legacy indices {@link #LEGACY_LISA} down to {@code 1}
	 */
	public static @NonNull List<Integer> legacyTiersHighestFirst() {
		return IntStream.rangeClosed(1, LEGACY_LISA).boxed().sorted(Comparator.reverseOrder()).toList();
	}
	
	/**
	 * Reduces a real tier to the v1 integer that comes closest to it.
	 * <p>
	 * {@link Difficulty#LISA} always answers {@link #LEGACY_LISA}: it is a named tier on both scales rather
	 * than a point on a line, and a v1 client showing "Lisa" for anything else would be lying about which
	 * game it is. Every numbered tier snaps to the nearest anchor, preferring the easier of two equally
	 * close ones - the same direction {@code DifficultyBands.nearestSupported} prefers, and the safer one to
	 * be wrong in.
	 *
	 * @param difficulty The real tier to reduce
	 * @return The legacy index, {@code 1}-{@link #LEGACY_LISA}
	 */
	public static int toLegacy(@NonNull Difficulty difficulty) {
		if (difficulty.isLisa()) {
			return LEGACY_LISA;
		}
		
		int nearest = 1;
		int nearestDistance = Integer.MAX_VALUE;
		for (int legacyIndex = 1; legacyIndex <= ANCHORS.length; legacyIndex++) {
			int distance = Math.abs(ANCHORS[legacyIndex - 1] - difficulty.index());
			// Strictly closer wins, so the lower anchor already in hand keeps a tie.
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearest = legacyIndex;
			}
		}
		return nearest;
	}
}
