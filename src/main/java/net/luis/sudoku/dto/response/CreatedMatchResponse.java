package net.luis.sudoku.dto.response;

import org.jspecify.annotations.NonNull;

/**
 * Response to creating or inviting to a match (server-spec 10.1).
 *
 * @param matchId the match
 * @param inviteToken the bearer token another player needs to join
 */
public record CreatedMatchResponse(@NonNull String matchId, @NonNull String inviteToken) {}
