package net.luis.sudoku.domain;

import net.luis.sudoku.match.MatchResult;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One player's participation in a match.
 *
 * @param matchId the match
 * @param userId the player
 * @param displayName their name, denormalised for convenience when rendering a finished match
 * @param joinedAt when they joined
 * @param result how it ended for them, or null while running
 */
public record MatchParticipant(
	@NonNull UUID matchId,
	@NonNull UUID userId,
	@NonNull String displayName,
	@NonNull Instant joinedAt,
	@Nullable MatchResult result
) {}
