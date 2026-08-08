package net.luis.sudoku.dto.response;

import org.jspecify.annotations.NonNull;

/**
 * Liveness plus the two numbers worth seeing at a glance during an operational incident.
 *
 * @param status {@code UP} while the server is serving and the database answers, {@code DOWN} otherwise,
 *   in which case the response also carries a 503
 * @param schemaVersion applied database schema version
 * @param activeMatches matches currently running in memory
 */
public record HealthResponse(@NonNull String status, int schemaVersion, int activeMatches) {}
