package net.luis.sudoku.dto.response;

/**
 * Response to the currency endpoints (server-spec 9a).
 *
 * @param balance the player's Rhubarb, derived from the ledger
 */
public record CurrencyResponse(long balance) {}
