package net.luis.sudoku.repository;

import net.luis.sudoku.currency.LedgerReason;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.condition.SqlCondition;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import net.luis.utils.io.database.type.SqlTypes;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.*;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code currency_ledger} (server-spec 9a).
 * <p>
 * Append-only. The balance is <strong>derived</strong>, never stored as a mutable integer: a stake
 * deducted at match start has to survive a restart, and live match state is memory-resident.
 */
public final class CurrencyLedgerRepository {
	
	/**
	 * The "this row was written on that date" predicate both date-bucketed queries share.
	 * <p>
	 * The date is evaluated in the server zone, matching how every other date in the schema is computed
	 * (spec 5): {@code created_at} is a {@code TIMESTAMPTZ}, so the calendar day it belongs to is only
	 * defined once a zone is named. Renders as {@code CAST((created_at AT TIME ZONE ?) AS DATE) = ?}.
	 */
	private static @NonNull SqlCondition dateIn(@NonNull ZoneId zone, @NonNull LocalDate date) {
		return Sql.equalTo(Sql.dateInZone(LEDGER_CREATED_AT, Sql.of(zone.getId(), SqlTypes.TEXT)), date);
	}
	
	/**
	 * Appends a ledger row.
	 * <p>
	 * {@code id} is a DB-generated identity column, which the query builder omits from the value tuple on
	 * its own because the column is declared {@code autoIncrement()}.
	 * <p>
	 * {@code createdAt} comes from the application clock rather than the database's {@code now()}. The
	 * daily earning cap and the once-per-date daily bonus both bucket rows by date in
	 * {@code SUDOKU_TIMEZONE}, and spec 5 requires those dates to be computed at write time - so the
	 * clock that decides "which day is it" must be the same one that stamps the row. Letting the
	 * database stamp it makes the cap silently wrong whenever the two disagree.
	 */
	public void append(@NonNull SqlTransaction transaction, @NonNull UUID userId, int delta, @NonNull LedgerReason reason, @Nullable UUID matchId, @NonNull Instant createdAt) throws SqlException {
		// The id passed here is never rendered - see above - so any value does.
		LedgerRow row = new LedgerRow(0L, userId, delta, reason, matchId, createdAt);
		transaction.from(CURRENCY_LEDGER).insert(row).execute();
	}
	
	/**
	 * @return the player's balance, summed over the whole ledger
	 */
	public long balance(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		Integer sum = transaction.from(CURRENCY_LEDGER).select(Sql.sum(LEDGER_DELTA)).where(Sql.equalTo(LEDGER_USER_ID, userId)).fetchOneOrNull();
		return sum == null ? 0L : sum;
	}
	
	/**
	 * Locks the player's ledger for the transaction, so a stake check and the stake insert cannot race
	 * another spend.
	 */
	public long balanceForUpdate(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		// Row-level locks over the player's existing rows are enough: any concurrent writer must read
		// the same rows to compute its own balance, so it blocks here.
		transaction.from(CURRENCY_LEDGER).select().where(Sql.equalTo(LEDGER_USER_ID, userId)).forUpdate().fetch();
		return this.balance(transaction, userId);
	}
	
	/**
	 * @return how many {@link LedgerReason#EARN_GAME} rows exist for a player on a date, which is what
	 *   the daily earning cap counts
	 */
	public int countEarnGamesOn(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull LocalDate date, @NonNull ZoneId zone) throws SqlException {
		Long count = transaction.from(CURRENCY_LEDGER).select(Sql.count(LEDGER_ID, false))
			.where(Sql.equalTo(LEDGER_USER_ID, userId))
			.where(Sql.equalTo(LEDGER_REASON, LedgerReason.EARN_GAME))
			.where(dateIn(zone, date))
			.fetchOneOrNull();
		return count == null ? 0 : Math.toIntExact(count);
	}
	
	/**
	 * @return whether the daily bonus has already been paid for a date, which must happen at most once
	 */
	public boolean hasEarnedDailyOn(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull LocalDate date, @NonNull ZoneId zone) throws SqlException {
		return transaction.from(CURRENCY_LEDGER).select()
			.where(Sql.equalTo(LEDGER_USER_ID, userId))
			.where(Sql.equalTo(LEDGER_REASON, LedgerReason.EARN_DAILY))
			.where(dateIn(zone, date))
			.exists();
	}
}
