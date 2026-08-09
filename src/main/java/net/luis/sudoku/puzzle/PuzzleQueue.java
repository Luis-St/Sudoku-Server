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
import java.time.*;
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
 * The whole {@link GeneratedPuzzle} is pooled, not just its key, so the givens are already computed by the
 * time a request arrives and serving one costs a bit-pack rather than a generation.
 * <p>
 * <strong>Sized for what it now serves.</strong> This began as the pool behind match creation - six bands,
 * Lisa excluded, a handful of buckets. Since {@code POST /api/v2/puzzles} it is the path every single
 * player game start takes, across fifteen bands and every size and variant, so both the worker count and
 * the bucket lifetime are set from that: several threads rather than one, so a band that costs seconds
 * cannot starve the ones that cost milliseconds, and buckets that fall out of use are dropped rather than
 * held at depth forever because someone opened them once.
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
	
	/** Enough to keep the dear buckets from starving the cheap ones, never enough to own the machine. */
	private static final int MAX_WORKER_THREADS = 4;
	
	/**
	 * How long a bucket may go untouched before it is dropped entirely.
	 * <p>
	 * The bucket space is the product of three axes and grew two and a half times when the difficulty
	 * scale did - around 150 combinations, against the handful any one server actually plays. Without
	 * this, every bucket a single curious player ever opened stayed pooled at full depth forever, and the
	 * pool's memory was decided by what had been asked for once rather than by what is in use. An hour is
	 * far longer than a session's gap between games and far shorter than a day.
	 */
	private static final Duration IDLE_BUCKET_TTL = Duration.ofHours(1);
	
	private final Map<Bucket, ConcurrentLinkedDeque<GeneratedPuzzle>> pools = new ConcurrentHashMap<>();
	private final Map<Bucket, AtomicInteger> refilling = new ConcurrentHashMap<>();
	/** When each bucket was last taken from, which is what {@link #IDLE_BUCKET_TTL} is measured against. */
	private final Map<Bucket, Instant> lastUsed = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();
	private final IntSupplier activePlayerCount;
	private final Clock clock;
	private final ExecutorService worker;
	
	public PuzzleQueue(@NonNull IntSupplier activePlayerCount) {
		this(activePlayerCount, Clock.systemUTC());
	}
	
	/**
	 * @param activePlayerCount How many players are connected, which sizes every pool
	 * @param clock The clock idle buckets are aged against
	 */
	public PuzzleQueue(@NonNull IntSupplier activePlayerCount, @NonNull Clock clock) {
		this.activePlayerCount = activePlayerCount;
		this.clock = clock;
		// Deliberately small, and deliberately no longer one.
		//
		// Single-threaded was right while this fed match creation alone: six bands, no Lisa, and a miss cost
		// tens of milliseconds. It now serves every single-player game start across fifteen bands including
		// Lisa, where the dearest combinations take seconds each - and one thread means a cold 16x16 high
		// band refilling to MIN_DEPTH is four of those in a row, with every other bucket's refill queued
		// behind it. Buckets that would have been warm are then missed, and a miss generates inline on a
		// request thread, so serializing the background work is what puts the work back in the foreground.
		//
		// Still leaves a core free, which was the real point: generation is CPU-bound and must not take the
		// last request thread's core on a small self-hosted box.
		int threads = Math.clamp(Runtime.getRuntime().availableProcessors() - 1L, 1, MAX_WORKER_THREADS);
		AtomicInteger counter = new AtomicInteger();
		this.worker = Executors.newFixedThreadPool(threads, runnable ->
			Thread.ofPlatform().name("puzzle-queue-" + counter.incrementAndGet()).daemon().unstarted(runnable));
	}
	
	/**
	 * Takes a ready puzzle, generating one inline if the bucket is empty.
	 * <p>
	 * Falling back to inline generation rather than blocking keeps a cold queue correct, just slower.
	 * <p>
	 * <strong>Every tier is poolable, Lisa included.</strong> This used to call
	 * {@code PuzzleFactory.requireMultiplayerSafe} first, which structurally excluded Lisa from the pool -
	 * and Lisa is both the most expensive band to generate and, now that {@code POST /api/v2/puzzles} serves
	 * single-player content from here, the one that most needs pooling. Refusing Lisa is a rule about
	 * <em>matches</em>, not about pre-generation, and it still runs where it belongs, in
	 * {@code MatchService.create}.
	 *
	 * @param size The grid size
	 * @param variant The region layout variant
	 * @param difficulty The target band
	 * @return A ready puzzle for that bucket
	 */
	public @NonNull GeneratedPuzzle take(@NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty) {
		Bucket bucket = new Bucket(size, variant, difficulty);
		this.lastUsed.put(bucket, this.clock.instant());
		
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
		} catch (RejectedExecutionException e) {
			// Shutting down; inline generation still serves every caller correctly.
			pending.set(0);
		}
	}
	
	private void refill(@NonNull Bucket bucket) {
		// Opportunistic rather than scheduled: a refill is the only moment this class is awake anyway, and
		// a sweep of a map of at most a few hundred entries is nothing beside the generation it precedes.
		// No timer thread to own, and a queue nobody is using stops sweeping because nobody is refilling.
		this.evictIdleBuckets();
		
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
	 * Drops every bucket untouched for {@link #IDLE_BUCKET_TTL}, pool and bookkeeping together.
	 * <p>
	 * The bucket being refilled right now cannot be evicted here: {@link #take} stamps it immediately
	 * before asking for the refill, so its timestamp is younger than this sweep. A bucket that is evicted
	 * and then asked for again simply misses once and refills, which is the same cost as its very first
	 * request and the reason this can be as blunt as it is.
	 */
	private void evictIdleBuckets() {
		Instant cutoff = this.clock.instant().minus(IDLE_BUCKET_TTL);
		for (Map.Entry<Bucket, Instant> entry : this.lastUsed.entrySet()) {
			if (entry.getValue().isAfter(cutoff)) {
				continue;
			}
			// Remove the stamp first: a take() racing with this re-stamps and repopulates, so the worst
			// case is a pool dropped a moment after it was wanted, not a bucket left permanently unpooled.
			this.lastUsed.remove(entry.getKey(), entry.getValue());
			this.pools.remove(entry.getKey());
			this.refilling.remove(entry.getKey());
			log.debug("Dropped idle puzzle bucket {}", entry.getKey());
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
