package net.luis.sudoku.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/matches/join}.
 * <p>
 * The code is the whole invitation, so there is no match id here: it resolves to the lobby on its own, and
 * the match id comes back in the response.
 *
 * @param code the match code the creator handed out, in any casing and with or without its grouping hyphen
 */
public record JoinByCodeRequest(@Nullable String code) {}
