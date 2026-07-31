package net.luis.sudoku.presence;

import net.luis.sudoku.match.MatchMode;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * A match request as the invited player receives it (feature-spec 9.7).
 * <p>
 * Half of this is the stored request and half is the match it names, joined at read time: {@code mode},
 * {@code stake} and {@code inviteToken} are the match's own, so a request cannot hand a client a token or
 * a stake that has since changed.
 *
 * @param id the request, so the invited player can dismiss exactly this one
 * @param matchId the match to join
 * @param inviteToken the match's ordinary join token - accepting is just {@code POST /matches/{id}/join}
 * @param mode which of the three multiplayer modes
 * @param stake Rhubarb each participant escrows, 0 for none
 * @param fromUserId who asked
 * @param fromDisplayName their name, so the invitation can be shown without a second request
 */
public record PendingMatchRequest(
	@NonNull UUID id,
	@NonNull UUID matchId,
	@NonNull String inviteToken,
	@NonNull MatchMode mode,
	int stake,
	@NonNull UUID fromUserId,
	@NonNull String fromDisplayName
) {}
