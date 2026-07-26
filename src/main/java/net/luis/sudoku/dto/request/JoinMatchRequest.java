package net.luis.sudoku.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/matches/{id}/join}.
 *
 * @param inviteToken the token handed out by the creator; required unless the caller is already a
 *   participant
 */
public record JoinMatchRequest(@Nullable String inviteToken) {}
