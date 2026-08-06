package net.luis.sudoku;

/**
 * The highest REST/WebSocket API version this server speaks.
 * <p>
 * Distinct from shared-core's {@code genVersion}: this one governs the shape of the HTTP contract,
 * that one governs puzzle generation. A client may need to reject on either.
 * <p>
 * <b>This constant does not build any route path.</b> It used to, and that was the trap: every route
 * hung off a single {@code PATH_PREFIX}, so bumping the version here would have silently moved the whole
 * API at once and left every client already in the wild with nothing to talk to.
 * <p>
 * <b>The versioning rule, since the app went live (2026-08-06):</b>
 * <ul>
 *     <li>Route paths are written out literally, {@code "/api/v1/daily"}, {@code "/ws/v1/matches/{id}"}.
 *     Never assembled from a constant. Grep for {@code "/api/v} to find them all.</li>
 *     <li>Changing a route's contract, a removed or repurposed field, a new required parameter, a different
 *     status code, means <b>registering it under the next version and leaving the old path registered and
 *     working</b>. Adding an optional field to a response is not a contract change and needs no bump.</li>
 *     <li><b>Only the previous version is kept.</b> Ship v3 for a route and its v1 goes away, so at most two
 *     versions of any one path exist. Routes version independently: most of the API can sit at v1 while one
 *     path is at v2.</li>
 *     <li>{@link #CURRENT} is the newest version any route has reached. It is reported to clients in
 *     {@code /server-info} so they can refuse a server that has moved past them. Bump it when a route
 *     reaches a version higher than it.</li>
 * </ul>
 * Schema changes carry their own version of this rule: new columns are added behind the {@code hasColumn}
 * guard so an older server binary still starts against a newer database.
 */
public final class ApiVersion {

	public static final int CURRENT = 1;

	private ApiVersion() {}
}
