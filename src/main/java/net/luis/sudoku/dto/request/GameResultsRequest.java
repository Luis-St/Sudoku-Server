package net.luis.sudoku.dto.request;

import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.stats.StatsService.PlayedGame;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Body of {@code POST /api/v1/stats/games} (server-spec 9): finished single-player games, uploaded as
 * they are played.
 * <p>
 * A list rather than a single game because the client queues what it could not send while offline, and
 * flushing that queue should not be one request per game.
 *
 * @param games the finished games
 */
public record GameResultsRequest(@Nullable List<PlayedGameBody> games) {
	
	public @NonNull List<PlayedGame> parseGames() {
		if (this.games == null || this.games.isEmpty()) {
			return List.of();
		}
		
		List<PlayedGame> parsed = new ArrayList<>(this.games.size());
		for (PlayedGameBody game : this.games) {
			if (game == null) {
				throw ApiException.badRequest("stats upload contains a null game");
			}
			parsed.add(game.toPlayedGame());
		}
		return parsed;
	}
	
	/**
	 * One finished game.
	 *
	 * @param gameId the client's id for this game, which is what makes a retry recognisable as one
	 * @param size grid edge length
	 * @param variant {@code CLASSIC} or {@code CHAOS}
	 * @param difficulty tier index 1-6; Lisa is a genuine single-player tier and is accepted here
	 * @param solved whether the grid was finished
	 * @param elapsedMs how long it took
	 * @param hintsUsed hints consumed
	 */
	public record PlayedGameBody(
		@Nullable String gameId,
		@Nullable Integer size,
		@Nullable String variant,
		@Nullable Integer difficulty,
		@Nullable Boolean solved,
		@Nullable Long elapsedMs,
		@Nullable Integer hintsUsed
	) {
		
		private static int require(@Nullable Integer value, @NonNull String field) {
			if (value == null) {
				throw ApiException.badRequest("Missing required field in stats upload: " + field);
			}
			return value;
		}
		
		private @NonNull PlayedGame toPlayedGame() {
			UUID id;
			try {
				id = UUID.fromString(Requests.require(this.gameId, "gameId").trim());
			} catch (IllegalArgumentException e) {
				throw ApiException.badRequest("gameId is not a UUID: " + this.gameId);
			}
			return new PlayedGame(
				id,
				require(this.size, "size"),
				Requests.require(this.variant, "variant").trim().toUpperCase(),
				require(this.difficulty, "difficulty"),
				this.solved != null && this.solved,
				this.elapsedMs == null ? 0 : this.elapsedMs,
				this.hintsUsed == null ? 0 : this.hintsUsed
			);
		}
	}
}
