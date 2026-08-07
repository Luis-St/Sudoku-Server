package net.luis.sudoku.dto.response;

/**
 * Response to {@code POST /api/v1/stats/games}.
 * <p>
 * {@code recorded} can legitimately be lower than the number of games sent, or zero: a game the server
 * had already accepted is a retry, and skipping it is the endpoint working. A client uses this to log,
 * never to decide whether to send again - a 200 means every game in the request is now on the server,
 * however many of them arrived just now.
 *
 * @param recorded how many of the uploaded games were new and folded into the aggregates
 */
public record GameResultsResponse(int recorded) {}
