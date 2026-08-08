package net.luis.sudoku.dto.request;

import net.luis.sudoku.error.ApiException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Body of {@code POST /api/v1/daily/streak/sync} (server-spec 8.3): the streak a client counted for
 * itself, offered to a server that may know about fewer days.
 * <p>
 * Deliberately small. The server does not want the client's history, only the two numbers it cannot
 * derive on its own - how long the run is, and which day it ends on. Everything else about the streak,
 * {@code longest} and {@code restorePoints} included, stays the server's to compute.
 *
 * @param current the client's consecutive-day count
 * @param lastCompletedDate {@code yyyy-MM-dd}, the most recent day that count includes
 */
public record StreakSyncRequest(@Nullable Integer current, @Nullable String lastCompletedDate) {
	
	public int requireCurrent() {
		if (this.current == null) {
			throw ApiException.badRequest("Missing required field: current");
		}
		return this.current;
	}
	
	public @NonNull LocalDate parseLastCompletedDate() {
		String value = Requests.require(this.lastCompletedDate, "lastCompletedDate");
		
		try {
			return LocalDate.parse(value);
		} catch (DateTimeParseException e) {
			throw ApiException.badRequest("Field lastCompletedDate must be yyyy-MM-dd, got: " + value);
		}
	}
}
