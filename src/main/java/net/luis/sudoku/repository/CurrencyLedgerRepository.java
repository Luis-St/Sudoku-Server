package net.luis.sudoku.repository;

import net.luis.sudoku.currency.LedgerReason;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.*;
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
	 * Appends a ledger row.
	 * <p>
	 * {@code id} is a DB-generated identity column the query builder's entity insert has no way to omit,
	 * so this stays raw SQL, same carve-out as {@code daily_results}.
	 * <p>
	 * {@code createdAt} comes from the application clock rather than the database's {@code now()}. The
	 * daily earning cap and the once-per-date daily bonus both bucket rows by date in
	 * {@code SUDOKU_TIMEZONE}, and spec 5 requires those dates to be computed at write time - so the
	 * clock that decides "which day is it" must be the same one that stamps the row. Letting the
	 * database stamp it makes the cap silently wrong whenever the two disagree.
	 */
	public void append(@NonNull SqlTransaction transaction, @NonNull UUID userId, int delta, @NonNull LedgerReason reason,
	                   @Nullable UUID matchId, @NonNull Instant createdAt) throws SqlException {
		String sql = "INSERT INTO currency_ledger (user_id, delta, reason, match_id, created_at) VALUES (?, ?, ?, ?, ?)";
		try (PreparedStatement statement = transaction.getConnection().prepareStatement(sql)) {
			statement.setObject(1, userId);
			statement.setInt(2, delta);
			statement.setString(3, reason.name());
			statement.setObject(4, matchId);
			statement.setTimestamp(5, java.sql.Timestamp.from(createdAt));
			statement.executeUpdate();
		} catch (SQLException e) {
			throw new SqlException("Failed to append ledger entry", e);
		}
	}
	
	/**
	 * @return the player's balance, summed over the whole ledger
	 */
	public long balance(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		Integer sum = transaction.from(CURRENCY_LEDGER).select(Sql.sum(LEDGER_DELTA))
			.where(Sql.equalTo(LEDGER_USER_ID, userId)).fetchOneOrNull();
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
	public int countEarnGamesOn(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull LocalDate date,
	                            @NonNull ZoneId zone) throws SqlException {
		// The date is evaluated in the server zone, matching how every other date in the schema is
		// computed (spec 5). No portable expression for "timestamptz AT TIME ZONE ... ::date" exists in
		// the query builder, so the predicate stays raw SQL while the rest of the query goes through it.
		String sql = """
			SELECT count(*) FROM currency_ledger
			 WHERE user_id = ? AND reason = 'EARN_GAME' AND (created_at AT TIME ZONE ?)::date = ?
			""";
		try (PreparedStatement statement = transaction.getConnection().prepareStatement(sql)) {
			statement.setObject(1, userId);
			statement.setString(2, zone.getId());
			statement.setObject(3, date);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getInt(1);
			}
		} catch (SQLException e) {
			throw new SqlException("Failed to count earn-game entries", e);
		}
	}
	
	/**
	 * @return whether the daily bonus has already been paid for a date, which must happen at most once
	 */
	public boolean hasEarnedDailyOn(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull LocalDate date,
	                                @NonNull ZoneId zone) throws SqlException {
		String sql = """
			SELECT 1 FROM currency_ledger
			 WHERE user_id = ? AND reason = 'EARN_DAILY' AND (created_at AT TIME ZONE ?)::date = ?
			 LIMIT 1
			""";
		try (PreparedStatement statement = transaction.getConnection().prepareStatement(sql)) {
			statement.setObject(1, userId);
			statement.setString(2, zone.getId());
			statement.setObject(3, date);
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		} catch (SQLException e) {
			throw new SqlException("Failed to check daily-earn entry", e);
		}
	}
}
