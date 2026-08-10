package net.luis.sudoku.puzzle;

import net.luis.sudoku.config.PoolConfig;
import net.luis.sudoku.db.Database;
import net.luis.sudoku.db.schema.Schema.PuzzlePoolRow;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.difficulty.DifficultyBands;
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
import java.util.function.Supplier;

/**
 * A pre-generation pool, so no request ever blocks on puzzle generation.
 * <p>
 * Generation is fast but not free - 12x12 and chaos at any size cost tens to hundreds of milliseconds, the
 * hard bands cost seconds, and 16x16 costs tens of seconds. A background worker keeps each
 * {@code (size, variant, difficulty)} bucket topped up, so match creation and every single-player game start
 * are instant.
 * <p>
 * The whole {@link GeneratedPuzzle} is pooled, not just its key, so the givens are already computed by the
 * time a request arrives and serving one costs a bit-pack rather than a generation.
 * <p>
 * <strong>The pool is {@code puzzle_pool} and nothing else.</strong> It used to live in a map in this class,
 * which made it per-process twice over: a restart threw away every puzzle the process had paid to generate,
 * and a second instance kept a pool of its own and duplicated the work. A shallow in-memory buffer survived
 * that change and has now gone too, deliberately: a buffered puzzle is one that a restart loses, that a
 * second instance cannot see, and that no {@code SELECT} can count, so "the pool is at least this deep" was
 * never a statement the table alone could answer. Every {@link #take} now reads the table. The cost is one
 * short transaction and a backtracking solve per request; the gain is that the depth guarantee is real.
 * <p>
 * <strong>A pooled puzzle is in exactly one place.</strong> Claiming deletes the row in the same transaction
 * that reads it, under {@code FOR UPDATE SKIP LOCKED}, so two instances can never hand the same puzzle to
 * two players - see {@link PuzzlePoolRepository}.
 * <p>
 * <strong>Every bucket is guaranteed a floor, and the floor is per size.</strong> {@link PoolConfig} carries
 * it, because 16x16 still costs two orders of magnitude more than 9x9 and its cost distribution has a tail
 * the others do not. {@link #warm()} fills every bucket the library says a size supports, cheapest sizes
 * first, so a cold pool serves the common sizes within seconds rather than after the 16x16 sweep; and
 * because the table is durable, that sweep is paid once per {@code genVersion} rather than once per deploy.
 * <p>
 * <strong>Nothing here needs the database to <em>stay</em> up.</strong> A failed claim is a miss and
 * generates inline; a failed store loses the puzzles rather than the request. A database outage makes the
 * server slower, never wrong. What it can no longer do is run without a database at all, which is the
 * deliberate price of the guarantee above.
 * <p>
 * <strong>Determinism is untouched.</strong> The queue only pre-generates puzzles for cases where the
 * server is free to choose the seed - normal and match play - drawing seeds from {@link SecureRandom}.
 * The daily is deliberately <em>not</em> queued: its key is fixed by {@code serverId ‖ date} and is
 * computed on demand, because inventing a seed for it would break the client's ability to derive the
 * same puzzle offline.
 */
public final class PuzzleQueue implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(PuzzleQueue.class);

	/** Enough to keep the dear buckets from starving the cheap ones, never enough to own the machine. */
	private static final int MAX_WORKER_THREADS = 4;

	/**
	 * How many further attempts a missed Lisa request gets, on top of its first.
	 * <p>
	 * Three, so a Lisa bucket makes at most four searches before settling for its best. Bounded rather than
	 * "until it lands", because the search can miss for reasons a fresh seed will not fix - at a size whose
	 * ceiling is below Lisa the request is snapped before it ever gets here, and an unbounded loop there would
	 * never terminate. See {@link #generateFor}.
	 */
	private static final int LISA_RETRIES = 3;

	/**
	 * How long one background generation may take before the refill gives up on its bucket for this round,
	 * at every size below 16x16.
	 * <p>
	 * Defence in depth, not the fix. The reason this exists is that {@code SolutionFiller} used to be able to
	 * search forever on an unlucky 16x16 seed: one seed was seen holding a core for 34 minutes. A refill worker
	 * is a fixed pool of at most {@link #MAX_WORKER_THREADS}, so four such seeds retired background generation
	 * permanently and every bucket then missed into inline generation on a request thread. The shared core now
	 * bounds its own search, so nothing should ever reach this timeout; it is here so that a future regression
	 * in the generator degrades into slow refills and a warning rather than into a pool that silently stops.
	 */
	private static final Duration GENERATION_TIMEOUT = Duration.ofSeconds(30);

	/**
	 * The same guard at 16x16, where the tail is long enough that thirty seconds is a thin margin.
	 * <p>
	 * <b>Not the emergency it looked like.</b> The 2026-08-09 sweep put 16x16 at 25.7 s average and 306 s
	 * worst, which would have made a flat thirty seconds a guarantee that these buckets never fill. Those
	 * numbers were measured <em>before</em> {@code SolutionFiller} gained its node bound later the same day,
	 * and they no longer describe this generator: re-measured on 2026-08-10 at genVersion 2, 16x16 classic
	 * averages 0.4 to 3.3 s per band with a 7.2 s worst case, and 16x16 chaos is cheaper still at under a
	 * second. Thirty seconds is already several times the worst case.
	 * <p>
	 * Two minutes rather than thirty seconds all the same, because the 16x16 fill distribution is
	 * heavy-tailed with no ceiling: the bound turns an unlucky seed into restarts rather than into a hang,
	 * and a seed that needs all eight restarts on a loaded box can still run far past the average. Paying
	 * that wait in the background is free; tripping the timeout is not, because it costs the bucket a round
	 * and pushes the next request inline.
	 */
	private static final Duration LARGE_GENERATION_TIMEOUT = Duration.ofMinutes(2);

	/**
	 * The order {@link #warm()} fills sizes in: cheapest first.
	 * <p>
	 * Not the enum's own order, and not a comparator on {@code n()}, because the point is cost rather than
	 * size - it just happens that measured cost is monotone in the edge length while the cost of a
	 * <em>band</em> at 16x16 is not. A cold server therefore has 9x9 ready in seconds instead of after the
	 * thirty 16x16 buckets, which is the difference between a deploy that serves and one that appears hung.
	 */
	private static final List<GridSize> WARM_ORDER = List.of(GridSize.FOUR, GridSize.SIX, GridSize.NINE, GridSize.TWELVE, GridSize.SIXTEEN);

	private final Map<Bucket, AtomicInteger> refilling = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();
	private final PuzzlePoolRepository pool = new PuzzlePoolRepository();
	private final IntSupplier activePlayerCount;
	private final Clock clock;
	private final Database database;
	private final PoolConfig config;
	private final ExecutorService worker;
	/**
	 * Where guarded background generations actually run, so that the generation timeout can be waited on
	 * from a worker thread. It has to be a second pool: a worker cannot time itself out, and running the
	 * generation on the pool that is waiting for it would deadlock as soon as both were busy. Unbounded and
	 * cached rather than fixed, because the whole point is that a wedged generation must not occupy a slot
	 * somebody else needs; an abandoned task still runs to completion, and its thread is reclaimed when it does.
	 */
	private final ExecutorService generator;

	/**
	 * @param activePlayerCount How many players are connected, which sizes every pool above its floor
	 * @param clock The clock that stamps stored rows
	 * @param database Where the pool is kept; required, since a pool that is not in the table is not a pool
	 * @param config The per-size floors, the ceiling and whether to warm at startup
	 */
	public PuzzleQueue(@NonNull IntSupplier activePlayerCount, @NonNull Clock clock, @NonNull Database database, @NonNull PoolConfig config) {
		this.activePlayerCount = Objects.requireNonNull(activePlayerCount, "Active player count must not be null");
		this.clock = Objects.requireNonNull(clock, "Clock must not be null");
		this.database = Objects.requireNonNull(database, "Database must not be null");
		this.config = Objects.requireNonNull(config, "Pool config must not be null");
		// Deliberately small, and deliberately no longer one.
		//
		// Single-threaded was right while this fed match creation alone: six bands, no Lisa, and a miss cost
		// tens of milliseconds. It now serves every single-player game start across fifteen bands including
		// Lisa, where the dearest combinations take seconds each - and one thread means a cold 16x16 high
		// band refilling to its floor is several of those in a row, with every other bucket's refill queued
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
	 * Queues a refill for every bucket the library supports, cheapest sizes first.
	 * <p>
	 * Called once at startup when {@link PoolConfig#warmOnStartup()} is set. It only <em>queues</em> the
	 * work - the sweep runs on the same bounded worker pool as every other refill, so a boot never blocks on
	 * generation and a request arriving mid-sweep is served by whatever is already in the table, or inline.
	 * <p>
	 * Sizes whose floor is zero are skipped entirely, which is what makes a floor of zero the way to opt out
	 * of pooling a size rather than a way to pool it shallowly.
	 *
	 * @return How many buckets were queued, for the boot log and for tests
	 */
	public int warm() {
		DifficultyBands bands = DifficultyBands.defaults();
		int queued = 0;
		for (GridSize size : WARM_ORDER) {
			if (this.config.minDepth(size) <= 0) {
				continue;
			}
			for (Variant variant : Variant.values()) {
				if (!variant.isSupportedAt(size)) {
					continue;
				}
				// Only the bands this size and variant can actually produce. Warming an unsupported band would
				// generate a puzzle that snaps to a neighbouring one, which is a row filed under a band the
				// grid does not have - and at 16x16 chaos that would be seven of the fifteen buckets.
				for (Difficulty band : bands.supported(size, variant)) {
					this.requestRefill(new Bucket(size, variant, band));
					queued++;
				}
			}
		}
		log.info("Queued {} puzzle buckets for warming", queued);
		return queued;
	}

	/**
	 * Takes a ready puzzle, generating one inline if the table has none.
	 * <p>
	 * Falling back to inline generation rather than blocking keeps a cold pool correct, just slower - and
	 * that is what a database this cannot reach degrades to as well.
	 * <p>
	 * <strong>Every tier is poolable, Lisa included</strong>, since {@code POST /api/v2/puzzles} serves it as
	 * an ordinary single-player band and it is the dearest tier to generate, so it is the one that most needs
	 * pooling. Refusing it here would only move that cost onto a request thread.
	 *
	 * @param size The grid size
	 * @param variant The region layout variant
	 * @param difficulty The target band
	 * @return A ready puzzle for that bucket
	 */
	public @NonNull GeneratedPuzzle take(@NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty) {
		Bucket bucket = new Bucket(size, variant, difficulty);
		GeneratedPuzzle pooled = this.claim(bucket, 1).stream().findFirst().orElse(null);
		// Asked for on every take, hit or miss: a hit has just made the bucket one shallower than its floor,
		// and a miss means it was already under it.
		this.requestRefill(bucket);
		if (pooled != null) {
			return pooled;
		}

		log.debug("Puzzle queue miss for {}, generating inline", bucket);
		return this.generateFor(bucket);
	}

	/**
	 * Generates one puzzle for a bucket, retrying a missed <b>Lisa</b> request before giving up on it.
	 * <p>
	 * <b>Lisa is the one band where a near miss costs more than a wrong label.</b> Every other tier is a
	 * rating and nothing else, so a request that lands a band low is served honestly by reporting the band it
	 * landed on. Lisa is also a <i>mode</i>: the client reads the band it is told and applies Lisa's fixed
	 * modifier set from it, so a miss does not merely mis-describe the puzzle, it silently gives the player a
	 * different game from the one they chose. Retrying is cheap by comparison - a fresh seed is a fresh search,
	 * and 9x9 classic lands Lisa on 32 of 32 measured seeds, so a second attempt almost always settles it.
	 * <p>
	 * {@link #LISA_RETRIES} retries after the first attempt, then <b>the hardest band any attempt reached</b>.
	 * Falling back rather than failing is deliberate: Lisa is the top of the scale, so every miss is downwards
	 * and the best attempt is the closest thing to what was asked for. Refusing instead would turn a rare
	 * near miss into a request that cannot be served at all.
	 * <p>
	 * Only the seed changes between attempts. The daily therefore cannot use this at all - its key is fixed by
	 * {@code serverId ‖ date} and inventing a seed for it would break the client's ability to derive the same
	 * puzzle offline - so a Lisa daily can still be a near miss. Match creation never reaches it either, since
	 * Lisa is refused for every multiplayer mode.
	 */
	private @NonNull GeneratedPuzzle generateFor(@NonNull Bucket bucket) {
		GeneratedPuzzle generated = withLisaRetries(bucket.difficulty(), () -> PuzzleFactory.generate(this.freshKey(bucket)));
		if (bucket.difficulty() == Difficulty.LISA && generated.rated() != Difficulty.LISA) {
			log.info("Lisa request for {} missed {} times; serving its hardest attempt, band {}",
				bucket, LISA_RETRIES + 1, generated.rated().index());
		}
		return generated;
	}

	/**
	 * The retry policy itself, separated from where the puzzles come from so it can be tested against a
	 * generator whose misses are chosen rather than hoped for.
	 *
	 * @param requested The band that was asked for
	 * @param attempt Produces one fresh generation per call; only the seed differs between calls
	 * @return A puzzle rated {@code requested} if any attempt reached it, otherwise the hardest-rated attempt
	 */
	static @NonNull GeneratedPuzzle withLisaRetries(@NonNull Difficulty requested, @NonNull Supplier<GeneratedPuzzle> attempt) {
		GeneratedPuzzle best = attempt.get();
		if (requested != Difficulty.LISA || best.rated() == Difficulty.LISA) {
			return best;
		}

		for (int retry = 0; retry < LISA_RETRIES; retry++) {
			GeneratedPuzzle next = attempt.get();
			if (next.rated() == Difficulty.LISA) {
				return next;
			}
			if (next.rated().index() > best.rated().index()) {
				best = next;
			}
		}
		return best;
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
	 * Generates one puzzle for the bucket, giving up after the size's generation timeout.
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
			// The retry loop lives inside the guarded task, so the timeout covers a Lisa bucket's whole set of
			// attempts rather than one of them. That is the right shape: what the guard protects is the worker
			// thread, and a bucket that spends four searches missing Lisa has occupied it for all four.
			pending = this.generator.submit(() -> this.generateFor(bucket));
		} catch (RejectedExecutionException e) {
			// Shutting down; the caller simply stops refilling.
			return null;
		}

		Duration timeout = generationTimeout(bucket.size());
		try {
			return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			pending.cancel(true);
			// Loud, because the shared core is supposed to make this unreachable. If this ever appears, the
			// generator's own bound has regressed and the pool is running degraded until it is fixed.
			log.warn("Generating a puzzle for {} exceeded {}; the bucket is left short for this round", bucket, timeout);
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

	/**
	 * @param size The grid size being generated
	 * @return How long a single background generation of that size may take
	 */
	static @NonNull Duration generationTimeout(@NonNull GridSize size) {
		return size == GridSize.SIXTEEN ? LARGE_GENERATION_TIMEOUT : GENERATION_TIMEOUT;
	}

	private void refill(@NonNull Bucket bucket) {
		int target = this.targetDepth(bucket.size());
		if (target <= 0) {
			return;
		}

		int stored;
		try {
			stored = this.database.read(transaction -> this.pool.count(transaction, bucket.size(), bucket.variant(), bucket.difficulty(), GenVersion.CURRENT));
		} catch (RuntimeException e) {
			log.warn("Could not read the pooled depth of {}; it keeps whatever it already holds", bucket, e);
			return;
		}

		// The count is read outside the write, so two instances refilling the same bucket at the same moment
		// both see the old shortfall and both fill it. That is deliberate: the alternative is holding a lock
		// across seconds - at 16x16, minutes - of generation, and the cost of being wrong is a bucket at twice
		// its target for as long as it takes to drain.
		int deficit = target - stored;
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
			rows.add(rowOf(generated, now));
		}

		if (rows.isEmpty()) {
			return;
		}

		try {
			this.database.execute(transaction -> this.pool.store(transaction, rows));
		} catch (RuntimeException e) {
			// The puzzles are lost rather than the request: the next refill tries again. Loud, because a pool
			// that cannot be written is not surviving restarts any more, and nothing else says so.
			log.warn("Could not store {} pre-generated puzzles for {}; the bucket stays short", rows.size(), bucket, e);
		}
	}

	/**
	 * Files a generated puzzle under the band it <b>rated</b>, not the one it was asked for.
	 * <p>
	 * The generator returns its closest candidate when the search misses, and that candidate can be several
	 * bands away - at 16x16 chaos above band 10 it always is. Filing it under the request would make the
	 * pool a durable, replicated store of mislabelled puzzles: every client claiming one would be told a
	 * band the grid does not have, and paid currency at that band's rate.
	 */
	private static @NonNull PuzzlePoolRow rowOf(@NonNull GeneratedPuzzle generated, @NonNull Instant now) {
		PuzzleKey key = generated.key();
		return new PuzzlePoolRow(0L, key.genVersion(), key.size(), key.variant(), generated.rated(), key.seed(), PuzzleFactory.encodeGivens(generated), now);
	}

	/**
	 * Takes rows out of the pool and rebuilds them into puzzles.
	 *
	 * @return the claimed puzzles, which is empty for any failure
	 */
	private @NonNull List<GeneratedPuzzle> claim(@NonNull Bucket bucket, int limit) {
		if (limit <= 0) {
			return List.of();
		}

		try {
			List<PuzzlePoolRow> rows = this.database.transaction(transaction ->
				this.pool.claim(transaction, bucket.size(), bucket.variant(), bucket.difficulty(), GenVersion.CURRENT, limit));

			List<GeneratedPuzzle> puzzles = new ArrayList<>(rows.size());
			for (PuzzlePoolRow row : rows) {
				// A backtracking solve, not a generate-dig-rate search: this is the whole reason the row
				// carries the givens and no solution. The row's band is the rated one, which is why it is
				// passed rather than left to default to the key's.
				PuzzleKey key = PuzzleKey.of(row.size(), row.variant(), row.difficulty(), row.seed());
				puzzles.add(PuzzleFactory.fromGivens(key, row.givens(), row.difficulty()));
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
	 * @param size The grid size
	 * @return the configured floor for that size, raised towards twice the active-player count and clamped
	 *   to the configured ceiling
	 */
	int targetDepth(@NonNull GridSize size) {
		int floor = this.config.minDepth(size);
		if (floor <= 0) {
			return 0;
		}

		int players = Math.max(0, this.activePlayerCount.getAsInt());
		return Math.clamp(players * 2L, floor, Math.max(floor, this.config.maxDepth()));
	}

	private @NonNull PuzzleKey freshKey(@NonNull Bucket bucket) {
		return PuzzleKey.of(bucket.size(), bucket.variant(), bucket.difficulty(), this.random.nextLong());
	}

	/**
	 * @param size The grid size
	 * @param variant The region layout variant
	 * @param difficulty The band
	 * @return how many puzzles this bucket holds, for diagnostics and tests
	 */
	public int depth(@NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty) {
		try {
			return this.database.read(transaction -> this.pool.count(transaction, size, variant, difficulty, GenVersion.CURRENT));
		} catch (RuntimeException e) {
			log.warn("Could not read the pooled depth of {}/{}/{}", size, variant, difficulty, e);
			return 0;
		}
	}

	@Override
	public void close() {
		this.worker.shutdownNow();
		this.generator.shutdownNow();
	}

	/**
	 * One pool bucket: everything that has to match for two puzzles to be interchangeable.
	 *
	 * @param size The grid size
	 * @param variant The region layout variant
	 * @param difficulty The band
	 */
	public record Bucket(@NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty) {}
}
