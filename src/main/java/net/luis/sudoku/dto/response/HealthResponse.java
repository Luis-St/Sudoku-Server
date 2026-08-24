package net.luis.sudoku.dto.response;

import org.jspecify.annotations.NonNull;

/**
 * Liveness plus what is worth seeing at a glance during an operational incident (server-spec 14).
 * <p>
 * {@code uptimeSeconds} is the one that catches a crash loop. A container that dies and is restarted every
 * few seconds answers {@code UP} every single time it is asked, so status alone reports a healthy server
 * where there is one that never finishes starting; an uptime that keeps resetting is the only field here
 * that says so.
 *
 * @param status {@code UP} while the server is serving and the database answers, {@code DOWN} otherwise,
 *   in which case the response also carries a 503
 * @param version the build this process is running, or {@code unknown} when the build stamp is missing;
 *   the only field here that answers whether a deploy actually landed, since status, uptime and schema
 *   version all read the same on the jar it replaced
 * @param uptimeSeconds how long this process has been serving, whole seconds
 * @param schemaVersion applied database schema version
 * @param activeMatches matches currently running in memory
 */
public record HealthResponse(@NonNull String status, @NonNull String version, long uptimeSeconds, int schemaVersion, int activeMatches) {}
