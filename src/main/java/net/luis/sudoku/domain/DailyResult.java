package net.luis.sudoku.domain;

import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One recorded attempt at a daily puzzle.
 *
 * @param id primary key
 * @param userId who played
 * @param date the daily's date in the server zone
 * @param difficulty tier index 1-15, Lisa included
 * @param attemptNo 1 for the first attempt, incrementing on each retry after a failure
 * @param outcome how it ended
 * @param elapsedMs wall time the player took
 * @param mistakes incorrect entries made
 * @param hintsUsed hints consumed
 * @param verified whether the solve-order replay check passed; unverified results are excluded from
 *   anything shown to other players (server-spec 8.2)
 * @param createdAt when the attempt was recorded
 */
public record DailyResult(
	long id,
	@NonNull UUID userId,
	@NonNull LocalDate date,
	int difficulty,
	int attemptNo,
	@NonNull DailyOutcome outcome,
	long elapsedMs,
	int mistakes,
	int hintsUsed,
	boolean verified,
	@NonNull Instant createdAt
) {}
