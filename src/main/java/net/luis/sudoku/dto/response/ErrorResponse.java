package net.luis.sudoku.dto.response;

import org.jspecify.annotations.NonNull;

import java.util.Map;

/**
 * The uniform error body every failed request returns (server-spec 13).
 *
 * @param error the stable {@link net.luis.sudoku.error.ErrorCode} name clients branch on
 * @param message human-readable explanation, for logs and developers - never parsed by clients
 * @param details optional structured context, empty rather than null when there is none
 */
public record ErrorResponse(@NonNull String error, @NonNull String message, @NonNull Map<String, Object> details) {}
