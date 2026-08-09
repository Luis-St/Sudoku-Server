package net.luis.sudoku.dto.response;

import net.luis.sudoku.domain.Match;
import net.luis.sudoku.domain.MatchParticipant;
import net.luis.sudoku.generation.GeneratedPuzzle;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A match as seen by v2 clients (server-spec 10.1).
 * <p>
 * Identical to {@link MatchResponse} except that {@code puzzleKey} is replaced by {@code puzzle}, which
 * carries the real fifteen-tier difficulty and the givens - so joining a match no longer costs the client
 * a full generation before the board can be drawn.
 *
 * @param matchId the match
 * @param mode which game
 * @param state where it is in the lifecycle
 * @param puzzle the grid, plus the key it was generated from
 * @param livesEnabled whether lives apply
 * @param hintsEnabled whether participants may spend hints
 * @param stake Rhubarb each participant escrows
 * @param winnerId the winner, or null
 * @param endReason why it ended, or null
 * @param participants who is in it
 */
public record MatchV2Response(
	@NonNull String matchId,
	@NonNull String mode,
	@NonNull String state,
	@NonNull PuzzleResponse puzzle,
	boolean livesEnabled,
	boolean hintsEnabled,
	int stake,
	@Nullable String winnerId,
	@Nullable String endReason,
	@NonNull List<MatchResponse.ParticipantResponse> participants
) {
	
	/**
	 * @param match The persisted match
	 * @param generated Its puzzle, rebuilt from the stored givens where there are any
	 * @param participants Who is in it
	 * @return The response body
	 */
	public static @NonNull MatchV2Response of(@NonNull Match match, @NonNull GeneratedPuzzle generated, @NonNull List<MatchParticipant> participants) {
		return new MatchV2Response(
			match.id().toString(),
			match.mode().name(),
			match.state().name(),
			PuzzleResponse.of(generated),
			match.livesEnabled(),
			match.hintsEnabled(),
			match.stake(),
			match.winnerId() == null ? null : match.winnerId().toString(),
			match.endReason() == null ? null : match.endReason().name(),
			participants.stream().map(MatchResponse.ParticipantResponse::of).toList()
		);
	}
}
