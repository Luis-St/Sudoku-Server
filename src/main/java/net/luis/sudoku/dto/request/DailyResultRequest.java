package net.luis.sudoku.dto.request;

import net.luis.sudoku.daily.SolveVerifier;
import net.luis.sudoku.domain.DailyOutcome;
import net.luis.sudoku.error.ApiException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Body of {@code POST /api/v1/daily/result} (server-spec 8.2).
 *
 * @param date {@code yyyy-MM-dd} the puzzle was played on - carried explicitly so an offline queue can
 *   report the date played rather than the date submitted (spec 8.4)
 * @param difficulty tier index 1-5
 * @param outcome {@code SOLVED} or {@code FAILED}
 * @param elapsedMs wall time taken
 * @param mistakes incorrect entries made
 * @param hintsUsed hints consumed
 * @param solveOrder ordered {@code [cell, digit]} pairs the player committed
 */
public record DailyResultRequest(
	@Nullable String date,
	@Nullable Integer difficulty,
	@Nullable String outcome,
	@Nullable Long elapsedMs,
	@Nullable Integer mistakes,
	@Nullable Integer hintsUsed,
	@Nullable List<List<Integer>> solveOrder
) {
	
	/**
	 * A full 16x16 has 256 cells, so anything beyond that is not a solve order. Bounding this before
	 * parsing stops a large body from turning into a large allocation.
	 */
	private static final int MAX_ENTRIES = 256;
	
	public @NonNull LocalDate parseDate() {
		String value = Requests.require(this.date, "date");
		try {
			return LocalDate.parse(value);
		} catch (DateTimeParseException e) {
			throw ApiException.badRequest("Field date must be yyyy-MM-dd, got: " + value);
		}
	}
	
	public int requireDifficulty() {
		return Requests.requirePositive(this.difficulty, "difficulty");
	}
	
	public @NonNull DailyOutcome parseOutcome() {
		return DailyOutcome.of(Requests.require(this.outcome, "outcome"));
	}
	
	public long requireElapsedMs() {
		if (this.elapsedMs == null) {
			throw ApiException.badRequest("Missing required field: elapsedMs");
		}
		if (this.elapsedMs < 0) {
			throw ApiException.badRequest("Field elapsedMs must not be negative");
		}
		return this.elapsedMs;
	}
	
	public int mistakesOrZero() {
		return this.mistakes == null || this.mistakes < 0 ? 0 : this.mistakes;
	}
	
	public int hintsUsedOrZero() {
		return this.hintsUsed == null || this.hintsUsed < 0 ? 0 : this.hintsUsed;
	}
	
	/**
	 * @return the solve order, empty for a {@code FAILED} submission which carries no completeness claim
	 */
	public @NonNull List<SolveVerifier.Entry> parseSolveOrder() {
		if (this.solveOrder == null) {
			return List.of();
		}
		if (this.solveOrder.size() > MAX_ENTRIES) {
			throw ApiException.badRequest("solveOrder holds more entries than the largest grid has cells");
		}
		
		List<SolveVerifier.Entry> entries = new ArrayList<>(this.solveOrder.size());
		for (List<Integer> pair : this.solveOrder) {
			if (pair == null || pair.size() != 2 || pair.get(0) == null || pair.get(1) == null) {
				throw ApiException.badRequest("Each solveOrder entry must be a [cell, digit] pair");
			}
			entries.add(new SolveVerifier.Entry(pair.get(0), pair.get(1)));
		}
		return entries;
	}
}
