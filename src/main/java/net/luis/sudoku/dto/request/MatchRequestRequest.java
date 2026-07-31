package net.luis.sudoku.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/matches/{id}/request}.
 *
 * @param userId the player to ask; they must be online, since the request is pushed over their
 *   presence socket rather than stored
 */
public record MatchRequestRequest(@Nullable String userId) {}
