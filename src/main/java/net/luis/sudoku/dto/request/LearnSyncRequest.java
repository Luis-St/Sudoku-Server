package net.luis.sudoku.dto.request;

import net.luis.sudoku.error.ApiException;
import io.javalin.openapi.OpenApiName;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Body of {@code POST /api/v1/learn/sync}: everything one device has finished in the learn area.
 * <p>
 * The whole set is sent rather than what has changed since some marker. There are at most nine rows per
 * technique and the merge keeps the better state whatever arrives, so a full set costs a few kilobytes and
 * removes the one thing a delta sync always needs, which is a cursor both sides agree on.
 *
 * @param entries the finished exercises, or null for a device with nothing to report
 */
public record LearnSyncRequest(@Nullable List<Entry> entries) {
	
	/** How many rows one call may carry: forty-one techniques of nine exercises, and a little room over. */
	private static final int MAX_ENTRIES = 500;
	
	public @NonNull List<Entry> requireEntries() {
		List<Entry> entries = this.entries == null ? List.of() : this.entries;
		if (entries.size() > MAX_ENTRIES) {
			throw ApiException.badRequest("Field entries must hold at most " + MAX_ENTRIES + " rows, got: " + entries.size());
		}
		return entries;
	}
	
	/**
	 * One finished exercise.
	 *
	 * @param technique the technique's enum name, which the client and the shared core already agree on
	 * @param level the one-based training level
	 * @param subLevel the zero-based exercise within it
	 * @param state {@code SOLVED} or {@code PARTIAL}
	 */
	@OpenApiName("LearnSyncEntry")
	public record Entry(@Nullable String technique, @Nullable Integer level, @Nullable Integer subLevel, @Nullable String state) {
		
		public @NonNull String requireTechnique() {
			return Requests.require(this.technique, "technique");
		}
		
		public int requireLevel() {
			return Requests.requirePositive(this.level, "level");
		}
		
		public int requireSubLevel() {
			if (this.subLevel == null || this.subLevel < 0) {
				throw ApiException.badRequest("Field subLevel must not be negative");
			}
			return this.subLevel;
		}
		
		/**
		 * The state, checked against the two that exist.
		 * <p>
		 * An unknown state is refused rather than stored: the client derives locked and open from the rows
		 * around a row, so a third value written here would come back as a state nothing knows how to draw.
		 */
		public @NonNull String requireState() {
			String state = Requests.require(this.state, "state");
			if (!"SOLVED".equals(state) && !"PARTIAL".equals(state)) {
				throw ApiException.badRequest("Field state must be SOLVED or PARTIAL, got: " + state);
			}
			return state;
		}
	}
}
