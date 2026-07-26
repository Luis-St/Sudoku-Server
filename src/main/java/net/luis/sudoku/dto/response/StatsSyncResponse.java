package net.luis.sudoku.dto.response;

/**
 * Response to {@code POST /api/v1/stats/sync}.
 *
 * @param merged how many aggregate rows were folded in
 */
public record StatsSyncResponse(int merged) {}
