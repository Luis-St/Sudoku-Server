package net.luis.sudoku.puzzle;

import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.key.PuzzleKey;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * A pre-generation pool, so no request ever blocks on puzzle generation.
 * <p>
 * Generation is fast but not free - 12x12 and 16x16, and chaos at any size, cost tens of milliseconds.
 * A background worker keeps each {@code (size, variant, difficulty)} bucket topped up to roughly twice
 * the active-player count, so match creation is instant.
 * <p>
 * <strong>Determinism is untouched.</strong> The queue only pre-generates puzzles for cases where the
 * server is free to choose the seed - normal and match play - drawing seeds from {@link SecureRandom}.
 * The daily is deliberately <em>not</em> queued: its key is fixed by {@code serverId ‖ date} and is
 * computed on demand, because inventing a seed for it would break the client's ability to derive the
 * same puzzle offline.
 */
public final class PuzzleQueue implements AutoCloseable {
	
	private static final Logger log = LoggerFactory.getLogger(PuzzleQueue.class);
	
	/** Even with no players connected, keep a few ready so the first request of the day is instant. */
	private static final int MIN_DEPTH = 4;
	
	/** Bounds memory: a puzzle is a few hundred bytes, but the bucket count is the product of three axes. */
	private static final int MAX_DEPTH = 32;
	
	private final Map<Bucket, ConcurrentLinkedDeque<GeneratedPuzzle>> pools = new ConcurrentHashMap<>();
	private final Map<Bucket, AtomicInteger> refilling = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();
	private final IntSupplier activePlayerCount;
	private final ExecutorService worker;
	
	public PuzzleQueue(@NonNull IntSupplier activePlayerCount) {
		this.activePlayerCount = activePlayerCount;
		// Single-threaded on purpose: generation is CPU-bound and this must never compete with request
		// threads for cores on a small self-hosted box.
		this.worker = Executors.newSingleThreadExecutor(runnable ->
			Thread.ofPlatform().name("puzzle-queue").daemon().unstarted(runnable));
	}
	
	/**
	 * Takes a ready puzzle, generating one inline if the bucket is empty.
	 * <p>
	 * Falling back to inline generation rather than blocking keeps a cold queue correct, just slower.
	 */
	public @NonNull GeneratedPuzzle take(@NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty) {
		PuzzleFactory.requireMultiplayerSafe(difficulty);
		Bucket bucket = new Bucket(size, variant, difficulty);
		
		GeneratedPuzzle pooled = this.pools.computeIfAbsent(bucket, _ -> new ConcurrentLinkedDeque<>()).poll();
		this.requestRefill(bucket);
		if (pooled != null) {
			return pooled;
		}
		
		log.debug("Puzzle queue miss for {}, generating inline", bucket);
		return PuzzleFactory.generate(this.freshKey(bucket));
	}
	
	/**
	 * Asks the worker to top this bucket up. Idempotent: a bucket already queued for refill is skipped,
	 * so a burst of misses cannot pile up redundant work.
	 */
	public void requestRefill(@NonNull Bucket bucket) {
		AtomicInteger pending = this.refilling.computeIfAbsent(bucket, _ -> new AtomicInteger());
		if (!pending.compareAndSet(0, 1)) {
			return;
		}
		try {
			this.worker.execute(() -> {
				try {
					this.refill(bucket);
				} catch (RuntimeException e) {
					log.warn("Failed to refill puzzle bucket {}", bucket, e);
				} finally {
					pending.set(0);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			// Shutting down; inline generation still serves every caller correctly.
			pending.set(0);
		}
	}
	
	private void refill(@NonNull Bucket bucket) {
		ConcurrentLinkedDeque<GeneratedPuzzle> pool = this.pools.computeIfAbsent(bucket, _ -> new ConcurrentLinkedDeque<>());
		int target = this.targetDepth();
		while (pool.size() < target) {
			pool.add(PuzzleFactory.generate(this.freshKey(bucket)));
		}
		// Trim if the player count dropped since the pool was filled.
		while (pool.size() > MAX_DEPTH) {
			pool.pollFirst();
		}
	}
	
	/**
	 * @return at least twice the active-player count, clamped to sane bounds
	 */
	int targetDepth() {
		int players = Math.max(0, this.activePlayerCount.getAsInt());
		return Math.clamp(players * 2L, MIN_DEPTH, MAX_DEPTH);
	}
	
	private @NonNull PuzzleKey freshKey(@NonNull Bucket bucket) {
		return PuzzleKey.of(bucket.size(), bucket.variant(), bucket.difficulty(), this.random.nextLong());
	}
	
	/**
	 * @return how many puzzles are currently pooled for this bucket, for tests and diagnostics
	 */
	public int depth(@NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty) {
		ConcurrentLinkedDeque<GeneratedPuzzle> pool = this.pools.get(new Bucket(size, variant, difficulty));
		return pool == null ? 0 : pool.size();
	}
	
	@Override
	public void close() {
		this.worker.shutdownNow();
	}
	
	/**
	 * One pool key: the three axes that fully determine what a pre-generated puzzle can be used for.
	 */
	public record Bucket(@NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty) {
		
		@Override
		public @NonNull String toString() {
			return this.size.n() + "x" + this.size.n() + " " + this.variant + " " + this.difficulty;
		}
	}
}
