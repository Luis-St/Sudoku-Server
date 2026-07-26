package net.luis.sudoku.dto.response;

import org.jspecify.annotations.NonNull;

/**
 * Unauthenticated server description, fetched by a client before it attempts to connect.
 * <p>
 * {@code genVersion} is the gate: a client whose shared-core generates different puzzles must refuse
 * to connect rather than silently disagree about what today's daily is (feature-spec 9).
 *
 * @param serverId 128-bit deployment identity as hex; seeds every daily puzzle
 * @param serverName display name shown to clients
 * @param timezone IANA zone id driving daily rollover
 * @param dailySize edge length of the daily grid
 * @param dailyVariant always {@code CLASSIC}
 * @param genVersion shared-core generation version this server generates with
 * @param apiVersion HTTP contract version
 */
public record ServerInfoResponse(
	@NonNull String serverId,
	@NonNull String serverName,
	@NonNull String timezone,
	int dailySize,
	@NonNull String dailyVariant,
	int genVersion,
	int apiVersion
) {}
