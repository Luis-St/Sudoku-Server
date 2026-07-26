package net.luis.sudoku.dto.response;

/**
 * Response to {@code GET}/{@code PUT} {@code /api/v1/preferences}.
 *
 * @param dailyDifficulty the standing tier, which applies from the next day
 */
public record PreferencesResponse(int dailyDifficulty) {}
