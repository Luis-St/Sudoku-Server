package net.luis.sudoku.currency;

import net.luis.sudoku.config.ServerConfig;
import net.luis.sudoku.db.Database;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.repository.CurrencyLedgerRepository;
import net.luis.sudoku.repository.StatsRepository;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The "Rhubarb" currency (server-spec 9a).
 * <p>
 * Minted server-side on verified results only, and tracked in an append-only ledger whose sum is the
 * balance. Nothing here ever writes a mutable balance column.
 */
public final class CurrencyService {
	
	private static final Logger log = LoggerFactory.getLogger(CurrencyService.class);
	
	/** Spec 9a.1: a solved normal game pays {@code 5 x difficultyIndex}. */
	public static final int PER_DIFFICULTY_INDEX = 5;
	
	/** Spec 9a.1: the daily pays the same, plus this bonus. */
	public static final int DAILY_BONUS = 20;
	
	private final Database database;
	private final CurrencyLedgerRepository ledger;
	private final StatsRepository stats;
	private final ServerConfig config;
	private final Clock clock;
	
	public CurrencyService(@NonNull Database database, @NonNull CurrencyLedgerRepository ledger, @NonNull StatsRepository stats, @NonNull ServerConfig config, @NonNull Clock clock) {
		this.database = database;
		this.ledger = ledger;
		this.stats = stats;
		this.config = config;
		this.clock = clock;
	}
	
	public long balance(@NonNull UUID userId) {
		return this.database.read(connection -> this.ledger.balance(connection, userId));
	}
	
	/**
	 * Awards currency for a solved normal game, subject to the per-day cap.
	 * <p>
	 * Runs inside the caller's transaction so the award and the result that earned it commit together.
	 *
	 * @return the amount awarded, or 0 once the daily cap is reached
	 */
	public int awardForGame(@NonNull SqlTransaction connection, @NonNull UUID userId, @NonNull Difficulty difficulty) throws SqlException {
		LocalDate today = this.today();
		int alreadyEarned = this.ledger.countEarnGamesOn(connection, userId, today, this.config.timezone());
		if (alreadyEarned >= this.config.currencyDailyGameCap()) {
			return 0;
		}
		
		int amount = PER_DIFFICULTY_INDEX * difficulty.index();
		this.ledger.append(connection, userId, amount, LedgerReason.EARN_GAME, null, this.clock.instant());
		return amount;
	}
	
	/**
	 * Awards currency for a solved daily. Paid once per date and deliberately outside the game cap
	 * (spec 9a.1).
	 *
	 * @return the amount awarded, or 0 if the bonus was already paid for that date
	 */
	public int awardForDaily(@NonNull SqlTransaction connection, @NonNull UUID userId, @NonNull Difficulty difficulty, @NonNull LocalDate date) throws SqlException {
		if (this.ledger.hasEarnedDailyOn(connection, userId, date, this.config.timezone())) {
			return 0;
		}
		
		int amount = PER_DIFFICULTY_INDEX * difficulty.index() + DAILY_BONUS;
		this.ledger.append(connection, userId, amount, LedgerReason.EARN_DAILY, null, this.clock.instant());
		return amount;
	}
	
	/**
	 * Reconciles a client's locally accumulated balance on connect (spec 9a.2).
	 * <p>
	 * A <strong>plausibility check</strong>, not a reconstruction: the server bounds what the player
	 * could conceivably have earned from the games it has recorded, and accepts anything at or below
	 * that. Clamping is applied <strong>silently</strong> - the response carries the reconciled balance
	 * with no indication that it was adjusted, because accusing an honest player of cheating over a
	 * clock skew is worse than quietly losing a few Rhubarb.
	 *
	 * @return the balance after reconciliation
	 */
	public long sync(@NonNull UUID userId, long reportedBalance, int reportedGamesPlayed) {
		return this.database.transaction(connection -> {
			long current = this.ledger.balanceForUpdate(connection, userId);
			if (reportedBalance <= current) {
				// The client has less than we already credited: nothing to add, and no reason to take
				// anything away.
				return current;
			}
			
			long plausibleCeiling = this.plausibleCeiling(connection, userId, reportedGamesPlayed);
			long accepted = Math.min(reportedBalance, plausibleCeiling);
			int delta = (int) Math.max(0, accepted - current);
			if (delta == 0) {
				return current;
			}
			
			this.ledger.append(connection, userId, delta, LedgerReason.SYNC_ADJUST, null, this.clock.instant());
			if (accepted < reportedBalance) {
				log.info("Clamped reported balance {} to {} for user {}", reportedBalance, accepted, userId);
			}
			return current + delta;
		});
	}
	
	/**
	 * The most a player could plausibly have earned offline.
	 * <p>
	 * Uses the larger of the games the server has on record and the games the client reports, then
	 * values every one of them at the maximum tier. Generous by design: this is a sanity bound on an
	 * unverifiable number, and false accusations cost more than false acceptances (spec 1.2).
	 */
	private long plausibleCeiling(@NonNull SqlTransaction connection, @NonNull UUID userId, int reportedGamesPlayed) throws SqlException {
		int recorded = this.stats.totalGamesPlayed(connection, userId);
		long games = Math.max(0, Math.max(recorded, reportedGamesPlayed));
		int maxPerGame = PER_DIFFICULTY_INDEX * Difficulty.LISA.index() + DAILY_BONUS;
		return games * maxPerGame;
	}
	
	/**
	 * Escrows a stake, refusing a player who cannot cover it (spec 9a.3).
	 *
	 * @throws ApiException {@code INSUFFICIENT_BALANCE} if the balance is below the stake
	 */
	public void escrowStake(@NonNull SqlTransaction connection, @NonNull UUID userId, int stake, @NonNull UUID matchId) throws SqlException {
		if (stake <= 0) {
			// Stake 0 means anyone may join, including a player with an empty balance.
			return;
		}
		
		long balance = this.ledger.balanceForUpdate(connection, userId);
		if (balance < stake) {
			throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "You need " + stake + " Rhubarb to join this match");
		}
		this.ledger.append(connection, userId, -stake, LedgerReason.STAKE, matchId, this.clock.instant());
	}
	
	public void payout(@NonNull SqlTransaction connection, @NonNull UUID winnerId, int pot, @NonNull UUID matchId) throws SqlException {
		if (pot > 0) {
			this.ledger.append(connection, winnerId, pot, LedgerReason.PAYOUT, matchId, this.clock.instant());
		}
	}
	
	public void refund(@NonNull SqlTransaction connection, @NonNull UUID userId, int stake, @NonNull UUID matchId) throws SqlException {
		if (stake > 0) {
			this.ledger.append(connection, userId, stake, LedgerReason.REFUND, matchId, this.clock.instant());
		}
	}
	
	private @NonNull LocalDate today() {
		return LocalDate.ofInstant(this.clock.instant(), this.config.timezone());
	}
	
	/**
	 * @param balance the reconciled balance
	 */
	public record Balance(long balance) {}
}
