package net.luis.sudoku.puzzle;

import net.luis.sudoku.db.Database;
import net.luis.sudoku.db.schema.Schema.PuzzlePoolRow;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.key.PuzzleKey;
import net.luis.sudoku.repository.PuzzlePoolRepository;
import net.luis.sudoku.version.GenVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * A pre-generation pool, so no request ever blocks on puzzle generation.
 * <p>
 * Generation is fast but not free - 12x12 and 16x16, and chaos at any size, cost tens of milliseconds, and
 * the hard bands cost seconds. A background worker keeps each {@code (size, variant, difficulty)} bucket
 * topped up to roughly twice the active-player count, so match creation is instant.
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
 * <strong>The pool lives in {@code puzzle_pool}, with a shallow buffer in front of it.</strong> It used to
 * live only in this map, which made it per-process twice over: a restart threw away every puzzle the
 * process had paid to generate, and a second instance kept a pool of its own and duplicated the work. The
 * table is now the pool, and the in-memory deque is deliberately kept to {@link #BUFFERED_DEPTH} entries -
 * enough that an ordinary {@link #take} is answered without touching the database, small enough that the
 * bulk of the pool is the part that survives. Holding the full depth in memory was the obvious alternative
 * and is exactly wrong: it would put the whole pool back where a restart loses it and where a second
 * instance cannot see it, leaving the table as bookkeeping rather than as the pool.
 * <p>
 * <strong>A pooled puzzle is in exactly one place.</strong> Claiming deletes the row in the same
 * transaction that reads it, under {@code FOR UPDATE SKIP LOCKED}, so the puzzles in this buffer are ones
 * no other instance can still see - see {@link PuzzlePoolRepository}. The price is that an ungraceful stop
 * loses whatever is buffered, at most {@link #BUFFERED_DEPTH} puzzles per bucket in use, which is a
 * generation or two rather than the whole pool.
 * <p>
 * <strong>Nothing here needs the database to be up.</strong> Every pool operation falls back to what this
 * class did before there was a table: a failed claim is a miss and generates inline, a failed store is a
 * puzzle that was pooled in memory instead. A queue constructed without a database - which is what the unit
 * tests do - is the old in-memory queue exactly.
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
	 * How many puzzles a bucket keeps in memory once the pool is a table.
	 * <p>
	 * Two, because this buffer exists to cover the latency of one request rather than to be the pool: a
	 * {@link #take} drains one and immediately asks for a refill that restores it, and the second entry is
	 * what keeps two requests arriving together from sending one of them to the database. Everything above
	 * that belongs in {@code puzzle_pool}, where a restart and a second instance can both still see it.
	 */
	private static final int BUFFERED_DEPTH = 2;
	
	/**
	 * How long a bucket may go untouched before it is dropped entirely.
	 * <p>
	 * The bucket space is the product of three axes and grew two and a half times when the difficulty
	 * scale did - around 150 combinations, against the handful any one server actually plays. Without
	 * this, every bucket a single curious player ever opened stayed pooled at full depth forever, and the
	 * pool's memory was decided by what had been asked for once rather than by what is in use. An hour is
	 * far longer than a session's gap between games and far shorter than a day.
	 * <p>
	 * It ages the <em>buffer</em>, not the table. A stored row costs about a hundred bytes and stays
	 * perfectly good until the generator version moves, so dropping it would only mean generating it again
	 * for the next player who opens that bucket; the whole table at full depth across every combination
	 * that exists is a few thousand rows.
	 */
	private static final Duration IDLE_BUCKET_TTL = Duration.ofHours(1);
	/**
	 * How long one background generation may take before the refill gives up on its bucket for this round.
	 * <p>
	 * Defence in depth, not the fix. The reason this exists is that {@code SolutionFiller} used to be able to
	 * search forever on an unlucky 16x16 seed: one seed was seen holding a core for 34 minutes. A refill worker
	 * is a fixed pool of at most {@link #MAX_WORKER_THREADS}, so four such seeds retired background generation
	 * permanently and every bucket then missed into inline generation on a request thread. The shared core now
	 * bounds its own search, so nothing should ever reach this timeout; it is here so that a future regression
	 * in the generator degrades into slow refills and a warning rather than into a pool that silently stops.
	 * <p>
	 * Thirty seconds is far above the worst measured cost of a single generation, the dearest 16x16 Lisa bucket,
	 * so a loaded box does not trip it.
	 */
	private static final Duration GENERATION_TIMEOUT = Duration.ofSeconds(30);

	private final Map<Bucket, ConcurrentLinkedDeque<GeneratedPuzzle>> pools = new ConcurrentHashMap<>();
	private final Map<Bucket, AtomicInteger> refilling = new ConcurrentHashMap<>();
	/** When each bucket was last taken from, which is what {@link #IDLE_BUCKET_TTL} is measured against. */
	private final Map<Bucket, Instant> lastUsed = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();
	private final PuzzlePoolRepository pool = new PuzzlePoolRepository();
	private final IntSupplier activePlayerCount;
	private final Clock clock;
	/** Null for a memory-only queue: see the class comment on why every path tolerates that. */
	private final @Nullable Database database;
	private final ExecutorService worker;
	/**
	 * Where guarded background generations actually run, so that {@link #GENERATION_TIMEOUT} can be waited on
	 * from a worker thread. It has to be a second pool: a worker cannot time itself out, and running the
	 * generation on the pool that is waiting for it would deadlock as soon as both were busy. Unbounded and
	 * cached rather than fixed, because the whole point is that a wedged generation must not occupy a slot
	 * somebody else needs; an abandoned task still runs to completion, and its thread is reclaimed when it does.
	 */
	private final ExecutorService generator;

	public PuzzleQueue(@NonNull IntSupplier activePlayerCount) {
		this(activePlayerCount, Clock.systemUTC());
	}
	
	/**
	 * @param activePlayerCount How many players are connected, which sizes every pool
	 * @param clock The clock idle buckets are aged against
	 */
	public PuzzleQueue(@NonNull IntSupplier activePlayerCount, @NonNull Clock clock) {
		this(activePlayerCount, clock, null);
	}
	
	/**
	 * @param activePlayerCount How many players are connected, which sizes every pool
	 * @param clock The clock idle buckets are aged against, and that stamps stored rows
	 * @param database Where the pool is kept, or {@code null} to keep it in memory only
	 */
	public PuzzleQueue(@NonNull IntSupplier activePlayerCount, @NonNull Clock clock, @Nullable Database database) {
		this.activePlayerCount = activePlayerCount;
		this.clock = clock;
		this.database = database;
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
		AtomicInteger generatorCounter = new AtomicInteger();
		this.generator = Executors.newCachedThreadPool(runnable ->
			Thread.ofPlatform().name("puzzle-gen-" + generatorCounter.incrementAndGet()).daemon().unstarted(runnable));

		this.dropOtherGenVersions();
	}
	
	/**
	 * Takes a ready puzzle, generating one inline if neither the buffer nor the table has one.
	 * <p>
	 * Falling back to inline generation rather than blocking keeps a cold queue correct, just slower - and
	 * that is what a database this cannot reach degrades to as well.
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
		
		GeneratedPuzzle pooled = this.buffer(bucket).poll();
		if (pooled == null) {
			// The buffer is only two deep, so an empty one is ordinary rather than exceptional: the pool
			// itself is the table, and this is the normal way a request reaches it.
			pooled = this.claim(bucket, 1).stream().findFirst().orElse(null);
		}
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
	
	/**
	 * Generates one puzzle for the bucket, giving up after {@link #GENERATION_TIMEOUT}.
	 * <p>
	 * Only the background paths call this. A miss still generates inline on the request thread unguarded,
	 * because abandoning that one means answering the request some other way, and there is no better answer
	 * to give: a 503 or a puzzle from a neighbouring bucket are both worse than a slow response, and the
	 * shared core bounds the wait now anyway.
	 * <p>
	 * Cancelling does not stop the work. A generation is CPU-bound and never checks its interrupt flag, so
	 * the abandoned thread runs to completion in the background; what this buys is that the refill worker
	 * stops waiting on it, which is the thread that actually matters.
	 *
	 * @return The generated puzzle, or {@code null} if it did not arrive in time
	 */
	private @Nullable GeneratedPuzzle generateGuarded(@NonNull Bucket bucket) {
		Future<GeneratedPuzzle> pending;
		try {
			pending = this.generator.submit(() -> PuzzleFactory.generate(this.freshKey(bucket)));
		} catch (RejectedExecutionException e) {
			// Shutting down; the caller simply stops refilling.
			return null;
		}

		try {
			return pending.get(GENERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			pending.cancel(true);
			// Loud, because the shared core is supposed to make this unreachable. If this ever appears, the
			// generator's own bound has regressed and the pool is running degraded until it is fixed.
			log.warn("Generating a puzzle for {} exceeded {}; the bucket is left short for this round", bucket, GENERATION_TIMEOUT);
			return null;
		} catch (ExecutionException e) {
			log.warn("Could not pre-generate a puzzle for {}; the bucket is left short for this round", bucket, e.getCause());
			return null;
		} catch (InterruptedException e) {
			pending.cancel(true);
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private void refill(@NonNull Bucket bucket) {
		// Opportunistic rather than scheduled: a refill is the only moment this class is awake anyway, and
		// a sweep of a map of at most a few hundred entries is nothing beside the generation it precedes.
		// No timer thread to own, and a queue nobody is using stops sweeping because nobody is refilling.
		this.evictIdleBuckets();
		
		ConcurrentLinkedDeque<GeneratedPuzzle> buffer = this.buffer(bucket);
		int target = this.targetDepth();
		
		this.topUpStore(bucket, target, buffer.size());
		
		int buffered = this.bufferTarget(target);
		buffer.addAll(this.claim(bucket, buffered - buffer.size()));
		// Whatever the table could not supply - because it is empty, because another instance got there
		// first, or because it is unreachable - is generated here, which is what the queue always did.
		while (buffer.size() < buffered) {
			GeneratedPuzzle generated = this.generateGuarded(bucket);
			if (generated == null) {
				break;
			}
			buffer.add(generated);
		}
		// Trim if the player count dropped since the buffer was filled.
		while (buffer.size() > MAX_DEPTH) {
			buffer.pollFirst();
		}
	}
	
	/**
	 * Generates whatever the durable pool is short of and appends it.
	 * <p>
	 * The count is read outside the write, so two instances refilling the same bucket at the same moment
	 * both see the old shortfall and both fill it. That is deliberate: the alternative is holding a lock
	 * across seconds of generation, and the cost of being wrong is a bucket at twice its target for as long
	 * as it takes to drain - bounded, self-correcting, and cheaper than serialising every instance's
	 * generation behind every other's.
	 */
	private void topUpStore(@NonNull Bucket bucket, int target, int buffered) {
		if (this.database == null) {
			return;
		}
		
		int stored;
		try {
			stored = this.database.read(transaction -> this.pool.count(transaction, bucket.size(), bucket.variant(), bucket.difficulty(), GenVersion.CURRENT));
		} catch (RuntimeException e) {
			log.warn("Could not read the pooled depth of {}; the bucket falls back to its in-memory buffer", bucket, e);
			return;
		}
		
		int deficit = target - stored - buffered;
		if (deficit <= 0) {
			return;
		}
		
		Instant now = this.clock.instant();
		List<PuzzlePoolRow> rows = new ArrayList<>(deficit);
		for (int i = 0; i < deficit; i++) {
			GeneratedPuzzle generated = this.generateGuarded(bucket);
			if (generated == null) {
				break;
			}
			PuzzleKey key = generated.key();
			rows.add(new PuzzlePoolRow(0L, key.genVersion(), key.size(), key.variant(), key.difficulty(), key.seed(), PuzzleFactory.encodeGivens(generated), now));
		}

		if (rows.isEmpty()) {
			return;
		}

		try {
			this.database.execute(transaction -> this.pool.store(transaction, rows));
		} catch (RuntimeException e) {
			// The puzzles are lost rather than the request: the buffer is filled below either way, and the
			// next refill tries again. Loud, because a pool that cannot be written is a pool that is not
			// surviving restarts any more, and nothing else says so.
			log.warn("Could not store {} pre-generated puzzles for {}; the pool is running from memory only", rows.size(), bucket, e);
		}
	}
	
	/**
	 * Takes rows out of the durable pool and rebuilds them into puzzles.
	 *
	 * @return the claimed puzzles, which is empty for a memory-only queue and for any failure
	 */
	private @NonNull List<GeneratedPuzzle> claim(@NonNull Bucket bucket, int limit) {
		if (this.database == null || limit <= 0) {
			return List.of();
		}
		
		try {
			List<PuzzlePoolRow> rows = this.database.transaction(transaction ->
				this.pool.claim(transaction, bucket.size(), bucket.variant(), bucket.difficulty(), GenVersion.CURRENT, limit));
			
			List<GeneratedPuzzle> puzzles = new ArrayList<>(rows.size());
			for (PuzzlePoolRow row : rows) {
				// A backtracking solve, not a generate-dig-rate search: this is the whole reason the row
				// carries the givens and no solution.
				puzzles.add(PuzzleFactory.fromGivens(PuzzleKey.of(row.size(), row.variant(), row.difficulty(), row.seed()), row.givens()));
			}
			return puzzles;
		} catch (RuntimeException e) {
			// Includes a row whose givens do not rebuild, which can only be a row this server did not write.
			// It has already been deleted by the claim, so the bad row cannot be hit twice.
			log.warn("Could not claim a pooled puzzle for {}; falling back to generating one", bucket, e);
			return List.of();
		}
	}
	
	/**
	 * Clears out puzzles some other generator version produced, once, at startup.
	 * <p>
	 * Serving one would put a client on a grid its own shared-core regenerates differently, which is the
	 * mismatch {@code GEN_VERSION_MISMATCH} exists to prevent on the wire; {@link #claim} filters them out
	 * as well, so this is about not carrying dead rows rather than about safety.
	 */
	private void dropOtherGenVersions() {
		if (this.database == null) {
			return;
		}
		
		try {
			int dropped = this.database.transaction(transaction -> this.pool.deleteOtherGenVersions(transaction, GenVersion.CURRENT));
			if (dropped > 0) {
				log.warn("Dropped {} pooled puzzles from an older generator version; the pool starts cold at genVersion {}", dropped, GenVersion.CURRENT);
			}
		} catch (RuntimeException e) {
			log.warn("Could not clear pooled puzzles from other generator versions", e);
		}
	}
	
	/**
	 * Drops every bucket untouched for {@link #IDLE_BUCKET_TTL}, buffer and bookkeeping together.
	 * <p>
	 * The bucket being refilled right now cannot be evicted here: {@link #take} stamps it immediately
	 * before asking for the refill, so its timestamp is younger than this sweep. A bucket that is evicted
	 * and then asked for again simply misses once and refills, which is the same cost as its very first
	 * request and the reason this can be as blunt as it is.
	 * <p>
	 * The buffered puzzles go with it rather than being written back. They are at most
	 * {@link #BUFFERED_DEPTH} per bucket, and a store on the eviction path would put a database write
	 * inside a sweep that runs on the generation worker.
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
	
	/**
	 * @return how much of {@link #targetDepth} is held in memory - all of it with no table to hold the
	 *   rest, and {@link #BUFFERED_DEPTH} otherwise
	 */
	private int bufferTarget(int target) {
		return this.database == null ? target : Math.min(target, BUFFERED_DEPTH);
	}
	
	private @NonNull ConcurrentLinkedDeque<GeneratedPuzzle> buffer(@NonNull Bucket bucket) {
		return this.pools.computeIfAbsent(bucket, _ -> new ConcurrentLinkedDeque<>());
	}
	
	private @NonNull PuzzleKey freshKey(@NonNull Bucket bucket) {
		return PuzzleKey.of(bucket.size(), bucket.variant(), bucket.difficulty(), this.random.nextLong());
	}
	
	/**
	 * @return how many puzzles this bucket holds <em>in memory</em>, for tests and diagnostics - which is
	 *   the whole pool only for a memory-only queue, and the buffer in front of {@code puzzle_pool}
	 *   otherwise
	 */
	public int depth(@NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty) {
		ConcurrentLinkedDeque<GeneratedPuzzle> buffer = this.pools.get(new Bucket(size, variant, difficulty));
		return buffer == null ? 0 : buffer.size();
	}
	
	@Override
	public void close() {
		this.worker.shutdownNow();
		this.generator.shutdownNow();
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
