package net.luis.sudoku.config;

import net.luis.sudoku.grid.GridSize;
import org.jspecify.annotations.NonNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * How deep the pre-generated puzzle pool is kept, per grid size (server-spec 10a).
 * <p>
 * <b>The floor is per size because generation cost is not uniform.</b> Measured on 2026-08-10 at
 * genVersion 2: 9x9 and 12x12 cost tens to hundreds of milliseconds a puzzle, 16x16 chaos under a second,
 * and 16x16 classic 0.4 to 3.3 s per band with a 7.2 s worst case. Only 16x16 has a tail worth planning
 * around - its fill distribution is heavy-tailed with no ceiling, bounded into restarts rather than into a
 * hang - so it is the one size where the number of rows a cold server commits to generating is a decision
 * rather than a detail.
 * <p>
 * The older figures this was first sized against - 25.7 s average, 306 s worst - predate
 * {@code SolutionFiller}'s node bound and no longer hold.
 * <p>
 * A floor of {@code 0} disables pooling for that size entirely, which is the honest way to opt out of
 * 16x16 pre-generation on a small box - those requests then generate inline, exactly as they did before
 * there was a pool.
 *
 * @param minDepth the guaranteed rows per bucket, by grid size; every size has an entry
 * @param maxDepth the ceiling the player-count scaling is clamped to, so a busy server cannot pool without
 *   bound
 * @param warmOnStartup whether to fill every bucket to its floor at boot, cheapest sizes first, rather
 *   than waiting for the first request to each bucket
 */
public record PoolConfig(@NonNull Map<GridSize, Integer> minDepth, int maxDepth, boolean warmOnStartup) {

	public PoolConfig {
		minDepth = new EnumMap<>(minDepth);
		for (GridSize size : GridSize.values()) {
			Integer depth = minDepth.get(size);
			if (depth == null) {
				throw new ConfigException("No pool floor configured for grid size " + size);
			}
			if (depth < 0) {
				throw new ConfigException(keyFor(size) + " must not be negative, got: " + depth);
			}
		}
		if (maxDepth < 1) {
			throw new ConfigException(EnvKeys.POOL_MAX_DEPTH + " must be at least 1, got: " + maxDepth);
		}
	}

	static @NonNull PoolConfig from(@NonNull Env env) {
		Map<GridSize, Integer> depths = new EnumMap<>(GridSize.class);
		// Owner's numbers, and they track the measured cost curve: deep where a puzzle is nearly free, and
		// shallow at 16x16, where two is a hedge against the tail rather than against the average. The cheap
		// sizes can afford ten because ten of them still cost less to warm than one 16x16 bucket does.
		depths.put(GridSize.FOUR, env.integer(EnvKeys.POOL_MIN_DEPTH_4, 10));
		depths.put(GridSize.SIX, env.integer(EnvKeys.POOL_MIN_DEPTH_6, 10));
		depths.put(GridSize.NINE, env.integer(EnvKeys.POOL_MIN_DEPTH_9, 10));
		depths.put(GridSize.TWELVE, env.integer(EnvKeys.POOL_MIN_DEPTH_12, 6));
		depths.put(GridSize.SIXTEEN, env.integer(EnvKeys.POOL_MIN_DEPTH_16, 2));
		return new PoolConfig(depths, env.integer(EnvKeys.POOL_MAX_DEPTH, 32), env.bool(EnvKeys.POOL_WARM_ON_STARTUP, true));
	}

	private static @NonNull String keyFor(@NonNull GridSize size) {
		return "SUDOKU_POOL_MIN_DEPTH_" + size.n();
	}

	/**
	 * @param size The grid size
	 * @return The guaranteed number of pooled puzzles per bucket at that size, {@code 0} for a size that is
	 *   deliberately not pooled
	 */
	public int minDepth(@NonNull GridSize size) {
		return this.minDepth.getOrDefault(size, 0);
	}

	/**
	 * @return The floors as an unmodifiable view
	 */
	@Override
	public @NonNull Map<GridSize, Integer> minDepth() {
		return Map.copyOf(this.minDepth);
	}
}
