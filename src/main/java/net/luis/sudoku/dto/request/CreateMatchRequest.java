package net.luis.sudoku.dto.request;

import net.luis.sudoku.compat.LegacyDifficulty;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.match.MatchMode;
import net.luis.sudoku.puzzle.PuzzleFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/matches} (server-spec 10.1).
 *
 * @param mode {@code RACE}, {@code DUEL} or {@code COOP}
 * @param config grid configuration
 * @param settings match settings chosen by the creator
 */
public record CreateMatchRequest(@Nullable String mode, @Nullable Config config, @Nullable Settings settings) {
	
	public @NonNull MatchMode requireMode() {
		return MatchMode.of(Requests.require(this.mode, "mode"));
	}
	
	public @NonNull Config requireConfig() {
		if (this.config == null) {
			throw ApiException.badRequest("Missing required field: config");
		}
		return this.config;
	}
	
	public @NonNull Settings settingsOrDefault() {
		return this.settings == null ? new Settings(null, null, null) : this.settings;
	}
	
	/**
	 * @param size grid edge length
	 * @param variant {@code CLASSIC} or {@code CHAOS}
	 * @param difficulty tier index 1-15; Lisa (15) is rejected for every mode
	 */
	public record Config(@Nullable Integer size, @Nullable String variant, @Nullable Integer difficulty) {
		
		public @NonNull GridSize requireSize() {
			if (this.size == null) {
				throw ApiException.badRequest("Missing required field: config.size");
			}
			
			try {
				return GridSize.ofEdgeLength(this.size);
			} catch (IllegalArgumentException e) {
				throw ApiException.badRequest("config.size must be 4, 6, 9, 12 or 16, got: " + this.size);
			}
		}
		
		public @NonNull Variant requireVariant() {
			String value = Requests.require(this.variant, "config.variant");
			
			try {
				return Variant.valueOf(value.trim().toUpperCase());
			} catch (IllegalArgumentException e) {
				throw ApiException.badRequest("config.variant must be CLASSIC or CHAOS, got: " + value);
			}
		}
		
		/**
		 * @return The real fifteen-tier band a v2 client asked for
		 * @throws ApiException {@code LISA_NOT_ALLOWED} for Lisa, {@code BAD_REQUEST} otherwise
		 */
		public @NonNull Difficulty requireDifficulty() {
			return PuzzleFactory.difficultyOfIndex(this.requireDifficultyIndex());
		}
		
		/**
		 * The same field read as the frozen six-tier integer a v1 client sends.
		 *
		 * @return The band that legacy tier names
		 * @throws ApiException {@code LISA_NOT_ALLOWED} for legacy 6, {@code BAD_REQUEST} otherwise
		 */
		public @NonNull Difficulty requireLegacyDifficulty() {
			Difficulty difficulty = LegacyDifficulty.fromLegacy(this.requireDifficultyIndex());
			PuzzleFactory.requireMultiplayerSafe(difficulty);
			return difficulty;
		}
		
		private int requireDifficultyIndex() {
			if (this.difficulty == null) {
				throw ApiException.badRequest("Missing required field: config.difficulty");
			}
			return this.difficulty;
		}
	}
	
	/**
	 * @param livesEnabled whether lives apply; defaults to false
	 * @param hintsEnabled whether participants may spend hints; defaults to true
	 * @param stake Rhubarb each participant escrows; defaults to 0, which lets anyone join
	 */
	public record Settings(@Nullable Boolean livesEnabled, @Nullable Boolean hintsEnabled, @Nullable Integer stake) {
		
		public boolean livesEnabledOrDefault() {
			return this.livesEnabled != null && this.livesEnabled;
		}
		
		/**
		 * Defaults to <em>true</em>, the opposite way round from lives.
		 * <p>
		 * Hints are available in single-player unless the difficulty forbids them, so a client that says
		 * nothing about them is asking for the ordinary game rather than for a harder one; and an older
		 * client, which cannot say anything, must not have its matches silently made stricter.
		 * </p>
		 *
		 * @return whether hints may be spent in this match
		 */
		public boolean hintsEnabledOrDefault() {
			return this.hintsEnabled == null || this.hintsEnabled;
		}
		
		public int stakeOrZero() {
			return this.stake == null ? 0 : this.stake;
		}
	}
}
