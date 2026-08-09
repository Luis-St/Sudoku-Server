package net.luis.sudoku.domain;

import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.key.PuzzleKey;
import net.luis.sudoku.match.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * The persisted half of a match (server-spec 10.4).
 * <p>
 * Only lifecycle is stored: mode, configuration, participants, winner, stake, timestamps. Live board
 * state is memory-resident and deliberately never written, which is why a restart abandons and refunds
 * rather than resuming.
 *
 * @param id primary key
 * @param mode which game
 * @param state where it is in the lifecycle
 * @param creatorId who created it and chose the configuration
 * @param size grid edge length
 * @param variant classic or chaos
 * @param difficulty tier index 1-15; Lisa is rejected for every mode (spec 10.1)
 * @param seed the puzzle seed, from which the key is rebuilt
 * @param givens the generated grid's givens, {@code GivensCodec}-encoded, or null for a match created
 *   before the column existed - see {@link net.luis.sudoku.match.MatchService#liveFor}
 * @param livesEnabled whether lives apply
 * @param hintsEnabled whether participants may spend hints; a match setting rather than a per-player one,
 *   so everybody on a shared board is playing the same game
 * @param stake Rhubarb each participant escrows; no minimum, no maximum
 * @param inviteToken bearer token allowing a join
 * @param winnerId the winner, or null while running or on a draw
 * @param endReason why it ended, or null while running
 * @param createdAt when it was created
 * @param startedAt when it moved to RUNNING, or null
 * @param endedAt when it finished, or null
 */
public record Match(
	@NonNull UUID id,
	@NonNull MatchMode mode,
	@NonNull MatchState state,
	@NonNull UUID creatorId,
	@NonNull GridSize size,
	@NonNull Variant variant,
	@NonNull Difficulty difficulty,
	long seed,
	@Nullable String givens,
	boolean livesEnabled,
	boolean hintsEnabled,
	int stake,
	@NonNull String inviteToken,
	@Nullable UUID winnerId,
	@Nullable EndReason endReason,
	@NonNull Instant createdAt,
	@Nullable Instant startedAt,
	@Nullable Instant endedAt
) {
	
	/**
	 * @return the key both participants regenerate the identical grid from
	 */
	public @NonNull PuzzleKey key() {
		return PuzzleKey.of(this.size, this.variant, this.difficulty, this.seed);
	}
}
