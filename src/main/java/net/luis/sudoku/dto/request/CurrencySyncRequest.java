package net.luis.sudoku.dto.request;

import net.luis.sudoku.error.ApiException;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/currency/sync} (server-spec 9a.2).
 *
 * @param reportedBalance the balance the client accumulated offline
 * @param gamesPlayed how many games the client believes it has played, used for the plausibility bound
 */
public record CurrencySyncRequest(@Nullable Long reportedBalance, @Nullable Integer gamesPlayed) {
	
	public long requireReportedBalance() {
		if (this.reportedBalance == null) {
			throw ApiException.badRequest("Missing required field: reportedBalance");
		}
		if (this.reportedBalance < 0) {
			throw ApiException.badRequest("Field reportedBalance must not be negative");
		}
		return this.reportedBalance;
	}
	
	public int gamesPlayedOrZero() {
		return this.gamesPlayed == null || this.gamesPlayed < 0 ? 0 : this.gamesPlayed;
	}
}
