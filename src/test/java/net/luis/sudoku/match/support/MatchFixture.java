package net.luis.sudoku.match.support;

import net.luis.sudoku.config.DuelConfig;
import net.luis.sudoku.config.MatchConfig;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.key.PuzzleKey;
import net.luis.sudoku.match.*;
import net.luis.sudoku.puzzle.PuzzleFactory;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Builders and helpers for the match-mode tests.
 */
public final class MatchFixture {
	
	/**
	 * A 4x4 grid keeps solve-to-completion tests fast while exercising the identical code path.
	 */
	public static final GridSize SIZE = GridSize.FOUR;
	
	private MatchFixture() {}
	
	public static @NonNull GeneratedPuzzle puzzle() {
		return PuzzleFactory.generate(PuzzleKey.of(SIZE, Variant.CLASSIC, Difficulty.TWO, 20260725L));
	}
	
	public static @NonNull Match match(@NonNull MatchMode mode, boolean livesEnabled, int stake,
	                                   @NonNull GeneratedPuzzle puzzle) {
		return match(mode, livesEnabled, true, stake, puzzle);
	}
	
	/** Hints default to true everywhere else, matching {@code Settings.hintsEnabledOrDefault}. */
	public static @NonNull Match match(@NonNull MatchMode mode, boolean livesEnabled, boolean hintsEnabled, int stake,
	                                   @NonNull GeneratedPuzzle puzzle) {
		return new Match(
			UUID.randomUUID(),
			mode,
			MatchState.WAITING,
			UUID.randomUUID(),
			SIZE,
			Variant.CLASSIC,
			Difficulty.TWO,
			puzzle.key().seed(),
			livesEnabled,
			hintsEnabled,
			stake,
			"invite-token",
			null,
			null,
			Instant.parse("2026-07-25T12:00:00Z"),
			null,
			null
		);
	}
	
	public static @NonNull MatchConfig matchConfig() {
		return new MatchConfig(60, 3);
	}
	
	public static @NonNull DuelConfig duelConfig() {
		return new DuelConfig(90, 6, 20, 180, 10, 0.5, 40);
	}
	
	/**
	 * A duel config with tiny values, so handover happens within a test's patience.
	 */
	public static @NonNull DuelConfig fastDuelConfig() {
		return new DuelConfig(1, 6, 20, 180, 1, 0.5, 2);
	}
	
	/**
	 * Tuned for the stalemate path specifically.
	 * <p>
	 * {@link #fastDuelConfig()} is not usable here: a correct entry there adds six seconds of bank, so
	 * reaching the handover cap after scoring takes over ten seconds. Capping {@code maxBank} at two
	 * seconds bounds every turn regardless of what the player earns.
	 */
	public static @NonNull DuelConfig stalemateDuelConfig() {
		return new DuelConfig(1, 1, 1, 2, 1, 0.5, 2);
	}
	
	/**
	 * Runs work on the match queue and blocks until it has been processed.
	 * <p>
	 * Every mutation goes through the queue, so a test that asserted immediately after submitting would
	 * race the executor. Draining is what makes these tests deterministic rather than flaky.
	 */
	public static void drain(@NonNull LiveMatch match) {
		CountDownLatch latch = new CountDownLatch(1);
		match.submit(latch::countDown);
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Match queue did not drain within 10s");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while draining the match queue", e);
		}
	}
	
	public static void connect(@NonNull LiveMatch match, @NonNull FakeConnection connection) {
		match.submit(() -> match.onConnect(connection));
		drain(match);
	}
	
	public static void send(@NonNull LiveMatch match, @NonNull FakeConnection from, @NonNull MessageType type,
	                        @NonNull Map<String, Object> payload) {
		match.submit(() -> match.onMessage(from.userId(), type, payload));
		drain(match);
	}
	
	public static void ready(@NonNull LiveMatch match, @NonNull FakeConnection... connections) {
		for (FakeConnection connection : connections) {
			send(match, connection, MessageType.READY, Map.of());
		}
	}
	
	public static void place(@NonNull LiveMatch match, @NonNull FakeConnection from, int cell, int digit) {
		send(match, from, MessageType.PLACE, Map.of("cell", cell, "digit", digit));
	}
	
	/**
	 * @return every empty cell index, in order, for the fixture puzzle
	 */
	public static @NonNull List<Integer> holes(@NonNull GeneratedPuzzle puzzle) {
		List<Integer> holes = new ArrayList<>();
		for (int index = 0; index < puzzle.puzzle().size().cellCount(); index++) {
			if (!puzzle.puzzle().cell(index).isGiven()) {
				holes.add(index);
			}
		}
		return holes;
	}
	
	/**
	 * @return every given cell index, in order, for the fixture puzzle - the complement of {@link #holes}
	 */
	public static @NonNull List<Integer> givens(@NonNull GeneratedPuzzle puzzle) {
		List<Integer> givens = new ArrayList<>();
		for (int index = 0; index < puzzle.puzzle().size().cellCount(); index++) {
			if (puzzle.puzzle().cell(index).isGiven()) {
				givens.add(index);
			}
		}
		return givens;
	}
	
	/**
	 * @return the digit that is NOT the solution for a cell, for driving mistake paths
	 */
	public static int wrongDigitFor(@NonNull GeneratedPuzzle puzzle, int cell) {
		int correct = puzzle.solutionAt(cell);
		return correct == 1 ? 2 : 1;
	}
	
	public static @NonNull EndReason reasonOf(@NonNull MessageEnvelope ended) {
		return EndReason.valueOf(String.valueOf(ended.payloadOrEmpty().get("reason")));
	}
}
