package net.luis.sudoku.dto.response;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * The answer to a presence heartbeat (server-spec 9.7).
 * <p>
 * It carries the pending match requests rather than nothing, because this is the one call every signed-in
 * client already makes on a timer: the alternative is a second poll at the same interval to learn the same
 * thing.
 *
 * @param onlineTtlSeconds how long this heartbeat keeps the caller online, so a client can pace itself
 *   against the server's own setting instead of assuming the default
 * @param requests match requests waiting for the caller, oldest first, empty when there are none
 */
public record HeartbeatResponse(int onlineTtlSeconds, @NonNull List<MatchRequestResponse> requests) {}
