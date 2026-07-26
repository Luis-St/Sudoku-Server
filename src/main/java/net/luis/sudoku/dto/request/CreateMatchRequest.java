package net.luis.sudoku.dto.request;

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
		return this.settings == null ? new Settings(null, null) : this.settings;
	}
	
	/**
	 * @param size grid edge length
	 * @param variant {@code CLASSIC} or {@code CHAOS}
	 * @param difficulty tier index 1-5; Lisa is rejected for every mode
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
		
		public @NonNull Difficulty requireDifficulty() {
			if (this.difficulty == null) {
				throw ApiException.badRequest("Missing required field: config.difficulty");
			}
			return PuzzleFactory.difficultyOfIndex(this.difficulty);
		}
	}
	
	/**
	 * @param livesEnabled whether lives apply; defaults to false
	 * @param stake Rhubarb each participant escrows; defaults to 0, which lets anyone join
	 */
	public record Settings(@Nullable Boolean livesEnabled, @Nullable Integer stake) {
		
		public boolean livesEnabledOrDefault() {
			return this.livesEnabled != null && this.livesEnabled;
		}
		
		public int stakeOrZero() {
			return this.stake == null ? 0 : this.stake;
		}
	}
}
