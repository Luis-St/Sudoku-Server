package net.luis.sudoku.dto.response;

import net.luis.sudoku.domain.Match;
import net.luis.sudoku.domain.MatchParticipant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A match as seen by clients (server-spec 10.1).
 * <p>
 * Carries the {@code puzzleKey} rather than the grid, exactly as the daily does.
 *
 * @param matchId the match
 * @param mode which game
 * @param state where it is in the lifecycle
 * @param puzzleKey what the client regenerates the grid from
 * @param livesEnabled whether lives apply
 * @param hintsEnabled whether participants may spend hints
 * @param stake Rhubarb each participant escrows
 * @param winnerId the winner, or null
 * @param endReason why it ended, or null
 * @param participants who is in it
 */
public record MatchResponse(
	@NonNull String matchId,
	@NonNull String mode,
	@NonNull String state,
	@NonNull PuzzleKeyResponse puzzleKey,
	boolean livesEnabled,
	boolean hintsEnabled,
	int stake,
	@Nullable String winnerId,
	@Nullable String endReason,
	@NonNull List<ParticipantResponse> participants
) {
	
	public static @NonNull MatchResponse of(@NonNull Match match, @NonNull List<MatchParticipant> participants) {
		return new MatchResponse(
			match.id().toString(),
			match.mode().name(),
			match.state().name(),
			PuzzleKeyResponse.of(match.key()),
			match.livesEnabled(),
			match.hintsEnabled(),
			match.stake(),
			match.winnerId() == null ? null : match.winnerId().toString(),
			match.endReason() == null ? null : match.endReason().name(),
			participants.stream().map(ParticipantResponse::of).toList()
		);
	}
	
	/**
	 * @param userId the player
	 * @param displayName their name
	 * @param result how it ended for them, or null while running
	 */
	public record ParticipantResponse(@NonNull String userId, @NonNull String displayName, @Nullable String result) {
		
		public static @NonNull ParticipantResponse of(@NonNull MatchParticipant participant) {
			return new ParticipantResponse(
				participant.userId().toString(),
				participant.displayName(),
				participant.result() == null ? null : participant.result().name()
			);
		}
	}
}
