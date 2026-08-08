package net.luis.sudoku.dto.response;

import net.luis.sudoku.presence.PendingMatchRequest;
import org.jspecify.annotations.NonNull;

/**
 * A match request waiting for the caller (server-spec 9.7).
 *
 * @param id this request, to pass back to {@code DELETE /match-requests/{id}} once it has been answered
 * @param matchId the match to join
 * @param inviteToken the match's join token, so accepting needs nothing else
 * @param mode {@code RACE}, {@code DUEL} or {@code COOP}
 * @param stake Rhubarb escrowed per participant
 * @param fromUserId who asked
 * @param fromDisplayName their name
 */
public record MatchRequestResponse(@NonNull String id, @NonNull String matchId, @NonNull String inviteToken, @NonNull String mode, int stake,
                                   @NonNull String fromUserId, @NonNull String fromDisplayName) {
	
	public static @NonNull MatchRequestResponse of(@NonNull PendingMatchRequest request) {
		return new MatchRequestResponse(
			request.id().toString(),
			request.matchId().toString(),
			request.inviteToken(),
			request.mode().name(),
			request.stake(),
			request.fromUserId().toString(),
			request.fromDisplayName()
		);
	}
}
