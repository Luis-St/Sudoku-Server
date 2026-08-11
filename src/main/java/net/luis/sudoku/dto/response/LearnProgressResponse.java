package net.luis.sudoku.dto.response;

import net.luis.sudoku.db.schema.Schema.LearnProgressRow;
import io.javalin.openapi.OpenApiName;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * What the server holds of a player's learn area progress, plus the two numbers a profile shows.
 *
 * @param entries every finished exercise the account has, across all of its devices
 * @param mastered how many techniques have every one of their exercises solved with the technique
 * @param accepted how many of the rows just sent were taken on, which is zero for a plain read
 */
public record LearnProgressResponse(@NonNull List<Entry> entries, int mastered, int accepted) {
	
	public static @NonNull LearnProgressResponse of(@NonNull List<LearnProgressRow> rows, int mastered, int accepted) {
		return new LearnProgressResponse(rows.stream().map(Entry::of).toList(), mastered, accepted);
	}
	
	/** One finished exercise. */
	@OpenApiName("LearnProgressEntry")
	public record Entry(@NonNull String technique, int level, int subLevel, @NonNull String state) {
		
		public static @NonNull Entry of(@NonNull LearnProgressRow row) {
			return new Entry(row.technique(), row.level(), row.subLevel(), row.state());
		}
	}
}
