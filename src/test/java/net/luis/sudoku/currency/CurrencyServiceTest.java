package net.luis.sudoku.currency;

import net.luis.sudoku.auth.SessionCloser;
import net.luis.sudoku.auth.SessionService;
import net.luis.sudoku.auth.SignatureVerifier;
import net.luis.sudoku.config.Env;
import net.luis.sudoku.config.EnvKeys;
import net.luis.sudoku.config.ServerConfig;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.repository.CurrencyLedgerRepository;
import net.luis.sudoku.repository.DeviceRepository;
import net.luis.sudoku.repository.InviteRepository;
import net.luis.sudoku.repository.SessionRepository;
import net.luis.sudoku.repository.StatsRepository;
import net.luis.sudoku.repository.UserRepository;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.sudoku.support.PostgresTest;
import net.luis.sudoku.support.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link CurrencyService}, covering server-spec 9a.
 */
class CurrencyServiceTest extends PostgresTest {

	private static final String BOOTSTRAP = "BOOTSTRAP1";
	private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

	private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

	private CurrencyLedgerRepository ledger;
	private StatsRepository stats;
	private InviteRepository invites;
	private RegistrationService registrations;
	private CurrencyService currency;
	private ServerConfig config;

	@BeforeEach
	void createServices() {
		this.now.set(NOW);
		Clock clock = new Clock() {
			@Override
			public ZoneId getZone() {
				return ZONE;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return CurrencyServiceTest.this.now.get();
			}
		};

		Map<String, String> env = new HashMap<>();
		env.put(EnvKeys.DB_URL, "jdbc:postgresql://db:5432/sudoku");
		env.put(EnvKeys.DB_USER, "sudoku");
		env.put(EnvKeys.DB_PASSWORD, "secret");
		env.put(EnvKeys.BOOTSTRAP_INVITE, BOOTSTRAP);
		env.put(EnvKeys.TIMEZONE, ZONE.getId());
		env.put(EnvKeys.CURRENCY_DAILY_GAME_CAP, "10");
		this.config = ServerConfig.from(Env.of(env));

		this.ledger = new CurrencyLedgerRepository();
		this.stats = new StatsRepository();
		this.invites = new InviteRepository();
		UserRepository users = new UserRepository();
		DeviceRepository devices = new DeviceRepository();

		SessionService sessionService = new SessionService(database, new SessionRepository(), users, devices,
			new CodeGenerator(), SessionCloser.NONE);
		this.registrations = new RegistrationService(database, users, devices, this.invites, sessionService,
			new SignatureVerifier(), clock);
		this.currency = new CurrencyService(database, this.ledger, this.stats, this.config, clock);
		this.registrations.ensureBootstrapInvite(BOOTSTRAP);
	}

	private Principal player(String name) {
		String code = BOOTSTRAP;
		if (!name.equals("Owner")) {
			code = "INV" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
			String finalCode = code;
			database.execute(connection -> this.invites.create(connection, finalCode, null, Role.NEW, null, this.now.get()));
		}
		TestKeys keys = TestKeys.ed25519(name);
		RegistrationService.Registered registered =
			this.registrations.register(code, name, keys.publicKey(), keys.algorithm(), "Phone");
		return new Principal(registered.user(), registered.device(), registered.session());
	}

	private int awardGame(UUID userId, Difficulty difficulty) {
		return database.transaction(connection -> this.currency.awardForGame(connection, userId, difficulty));
	}

	/**
	 * Stake and payout rows carry a real FK to {@code matches}, so tests need an actual match row rather
	 * than a fabricated id.
	 */
	private UUID match(UUID creatorId, int stake) {
		return database.transaction(connection -> {
			String sql = """
				INSERT INTO matches (mode, state, creator_id, size, variant, difficulty, seed, stake, invite_token)
				VALUES ('DUEL', 'WAITING', ?, 9, 'CLASSIC', 3, 42, ?, 'token')
				RETURNING id
				""";
			try (var statement = connection.getConnection().prepareStatement(sql)) {
				statement.setObject(1, creatorId);
				statement.setInt(2, stake);
				try (var result = statement.executeQuery()) {
					result.next();
					return result.getObject(1, UUID.class);
				}
			} catch (java.sql.SQLException e) {
				throw new net.luis.utils.io.database.exception.SqlException("Failed to insert test match", e);
			}
		});
	}

	private int awardDaily(UUID userId, Difficulty difficulty, LocalDate date) {
		return database.transaction(connection -> this.currency.awardForDaily(connection, userId, difficulty, date));
	}

	// --- earning (spec 9a.1) ---

	@Test
	void balance_forANewPlayer_isZero() {
		Principal player = this.player("Owner");
		assertEquals(0, this.currency.balance(player.userId()));
	}

	@Test
	void awardForGame_paysFiveTimesTheDifficultyIndex() {
		Principal player = this.player("Owner");

		int awarded = this.awardGame(player.userId(), Difficulty.THREE);

		assertAll(
			() -> assertEquals(15, awarded),
			() -> assertEquals(15, this.currency.balance(player.userId()))
		);
	}

	@Test
	void awardForGame_atEveryTier_scalesLinearly() {
		Principal player = this.player("Owner");

		assertAll(
			() -> assertEquals(5, this.awardGame(player.userId(), Difficulty.ONE)),
			() -> assertEquals(10, this.awardGame(player.userId(), Difficulty.TWO)),
			() -> assertEquals(25, this.awardGame(player.userId(), Difficulty.FIVE)),
			() -> assertEquals(30, this.awardGame(player.userId(), Difficulty.LISA), "Lisa is index 6")
		);
	}

	@Test
	void awardForGame_beyondTheDailyCap_paysNothing() {
		Principal player = this.player("Owner");
		for (int i = 0; i < this.config.currencyDailyGameCap(); i++) {
			this.awardGame(player.userId(), Difficulty.ONE);
		}

		int overCap = this.awardGame(player.userId(), Difficulty.ONE);

		assertAll(
			() -> assertEquals(0, overCap),
			() -> assertEquals(5 * this.config.currencyDailyGameCap(), this.currency.balance(player.userId()))
		);
	}

	@Test
	void awardForGame_theNextDay_startsTheCapAfresh() {
		Principal player = this.player("Owner");
		for (int i = 0; i < this.config.currencyDailyGameCap(); i++) {
			this.awardGame(player.userId(), Difficulty.ONE);
		}

		this.now.set(NOW.plus(java.time.Duration.ofDays(1)));

		assertEquals(5, this.awardGame(player.userId(), Difficulty.ONE));
	}

	@Test
	void awardForDaily_paysTheTierPlusTheBonus() {
		Principal player = this.player("Owner");
		LocalDate date = LocalDate.ofInstant(NOW, ZONE);

		int awarded = this.awardDaily(player.userId(), Difficulty.THREE, date);

		assertEquals(5 * 3 + CurrencyService.DAILY_BONUS, awarded);
	}

	@Test
	void awardForDaily_twiceOnOneDate_paysOnlyOnce() {
		Principal player = this.player("Owner");
		LocalDate date = LocalDate.ofInstant(NOW, ZONE);

		int first = this.awardDaily(player.userId(), Difficulty.THREE, date);
		int second = this.awardDaily(player.userId(), Difficulty.THREE, date);

		assertAll(
			() -> assertEquals(35, first),
			() -> assertEquals(0, second),
			() -> assertEquals(35, this.currency.balance(player.userId()))
		);
	}

	@Test
	void awardForDaily_sitsOutsideTheNormalGameCap() {
		// Spec 9a.1: "the daily sits outside the cap".
		Principal player = this.player("Owner");
		for (int i = 0; i < this.config.currencyDailyGameCap(); i++) {
			this.awardGame(player.userId(), Difficulty.ONE);
		}

		int daily = this.awardDaily(player.userId(), Difficulty.ONE, LocalDate.ofInstant(NOW, ZONE));

		assertEquals(5 + CurrencyService.DAILY_BONUS, daily);
	}

	// --- connect-time sync (spec 9a.2) ---

	@Test
	void sync_aPlausibleBalance_isAccepted() {
		Principal player = this.player("Owner");
		database.execute(connection -> this.stats.record(connection, player.userId(), 9, "CLASSIC", 3, true, 60_000, 0));

		long reconciled = this.currency.sync(player.userId(), 40, 1);

		assertEquals(40, reconciled);
	}

	@Test
	void sync_anImplausibleBalance_isClampedSilently() {
		Principal player = this.player("Owner");
		// One recorded game: the ceiling is one game at the richest possible rate.
		database.execute(connection -> this.stats.record(connection, player.userId(), 9, "CLASSIC", 3, true, 60_000, 0));
		int ceiling = CurrencyService.PER_DIFFICULTY_INDEX * Difficulty.LISA.index() + CurrencyService.DAILY_BONUS;

		long reconciled = this.currency.sync(player.userId(), 1_000_000, 1);

		assertAll(
			() -> assertEquals(ceiling, reconciled),
			() -> assertEquals(ceiling, this.currency.balance(player.userId()))
		);
	}

	@Test
	void sync_writesASyncAdjustLedgerRow() {
		Principal player = this.player("Owner");
		database.execute(connection -> this.stats.record(connection, player.userId(), 9, "CLASSIC", 3, true, 60_000, 0));

		this.currency.sync(player.userId(), 30, 1);

		int adjustments = database.read(connection -> {
			try (var statement = connection.getConnection().prepareStatement(
				"SELECT count(*) FROM currency_ledger WHERE user_id = ? AND reason = 'SYNC_ADJUST'")) {
				statement.setObject(1, player.userId());
				try (var result = statement.executeQuery()) {
					result.next();
					return result.getInt(1);
				}
			} catch (java.sql.SQLException e) {
				throw new net.luis.utils.io.database.exception.SqlException("Failed to count sync-adjust rows", e);
			}
		});
		assertEquals(1, adjustments);
	}

	@Test
	void sync_aBalanceBelowWhatTheServerAlreadyCredited_takesNothingAway() {
		Principal player = this.player("Owner");
		this.awardGame(player.userId(), Difficulty.FIVE);

		long reconciled = this.currency.sync(player.userId(), 1, 1);

		assertEquals(25, reconciled, "the server's own record wins over a lower client report");
	}

	@Test
	void sync_withNoRecordedGames_clampsToZero() {
		Principal player = this.player("Owner");

		long reconciled = this.currency.sync(player.userId(), 5_000, 0);

		assertEquals(0, reconciled);
	}

	// --- stakes (spec 9a.3) ---

	@Test
	void escrowStake_withEnoughBalance_deductsIt() {
		Principal player = this.player("Owner");
		this.awardGame(player.userId(), Difficulty.FIVE);
		UUID matchId = this.match(player.userId(), 10);

		database.execute(connection -> this.currency.escrowStake(connection, player.userId(), 10, matchId));

		assertEquals(15, this.currency.balance(player.userId()));
	}

	@Test
	void escrowStake_belowTheStake_isRejected() {
		Principal player = this.player("Owner");
		UUID matchId = this.match(player.userId(), 10);

		ApiException e = assertThrows(ApiException.class,
			() -> database.execute(connection -> this.currency.escrowStake(connection, player.userId(), 10, matchId)));

		assertAll(
			() -> assertEquals(ErrorCode.INSUFFICIENT_BALANCE, e.code()),
			() -> assertEquals(409, e.status()),
			() -> assertEquals(0, this.currency.balance(player.userId()))
		);
	}

	@Test
	void escrowStake_ofZero_isAllowedWithAnEmptyBalance() {
		// Spec 9a.3: stake 0 means anyone may join, including a player with nothing.
		Principal player = this.player("Owner");
		UUID matchId = this.match(player.userId(), 0);

		assertDoesNotThrow(() ->
			database.execute(connection -> this.currency.escrowStake(connection, player.userId(), 0, matchId)));
		assertEquals(0, this.currency.balance(player.userId()));
	}

	@Test
	void payoutAndRefund_moveTheExpectedAmounts() {
		Principal winner = this.player("Owner");
		Principal loser = this.player("Loser");
		this.awardGame(winner.userId(), Difficulty.FIVE);
		this.awardGame(loser.userId(), Difficulty.FIVE);
		UUID matchId = this.match(winner.userId(), 10);

		database.execute(connection -> {
			this.currency.escrowStake(connection, winner.userId(), 10, matchId);
			this.currency.escrowStake(connection, loser.userId(), 10, matchId);
			this.currency.payout(connection, winner.userId(), 20, matchId);
		});

		assertAll(
			() -> assertEquals(35, this.currency.balance(winner.userId()), "25 - 10 stake + 20 pot"),
			() -> assertEquals(15, this.currency.balance(loser.userId()), "25 - 10 stake")
		);
	}

	@Test
	void refund_returnsTheStakeToBothPlayers() {
		Principal first = this.player("Owner");
		Principal second = this.player("Second");
		this.awardGame(first.userId(), Difficulty.FIVE);
		this.awardGame(second.userId(), Difficulty.FIVE);
		UUID matchId = this.match(first.userId(), 10);

		database.execute(connection -> {
			this.currency.escrowStake(connection, first.userId(), 10, matchId);
			this.currency.escrowStake(connection, second.userId(), 10, matchId);
			this.currency.refund(connection, first.userId(), 10, matchId);
			this.currency.refund(connection, second.userId(), 10, matchId);
		});

		assertAll(
			() -> assertEquals(25, this.currency.balance(first.userId())),
			() -> assertEquals(25, this.currency.balance(second.userId()))
		);
	}

	@Test
	void balance_isDerivedFromTheLedgerRatherThanStored() {
		// The ledger is the source of truth; there is no mutable balance column to drift from it.
		Principal player = this.player("Owner");
		this.awardGame(player.userId(), Difficulty.THREE);
		UUID matchId = this.match(player.userId(), 5);
		database.execute(connection -> this.currency.escrowStake(connection, player.userId(), 5, matchId));

		long summed = database.read(connection -> this.ledger.balance(connection, player.userId()));

		assertAll(
			() -> assertEquals(10, summed),
			() -> assertEquals(summed, this.currency.balance(player.userId()))
		);
	}

	@Test
	void statsRecord_afterOneSolveAndOneFailure_aggregatesCorrectly() {
		Principal player = this.player("Owner");
		database.execute(connection -> {
			this.stats.record(connection, player.userId(), 9, "CLASSIC", 3, true, 60_000, 1);
			this.stats.record(connection, player.userId(), 9, "CLASSIC", 3, false, 0, 0);
			this.stats.record(connection, player.userId(), 9, "CLASSIC", 3, true, 40_000, 0);
		});

		var entries = database.read(connection -> this.stats.findByUser(connection, player.userId()));

		assertAll(
			() -> assertEquals(1, entries.size()),
			() -> assertEquals(3, entries.getFirst().gamesPlayed()),
			() -> assertEquals(2, entries.getFirst().solved()),
			() -> assertEquals(1, entries.getFirst().failed()),
			() -> assertEquals(40_000L, entries.getFirst().bestTimeMs()),
			() -> assertEquals(50_000L, entries.getFirst().averageTimeMs(), "mean of the two solves"),
			() -> assertEquals(1, entries.getFirst().hintsUsed())
		);
	}

	@Test
	void statsRecord_aFailureFirst_leavesBestTimeUnset() {
		Principal player = this.player("Owner");
		database.execute(connection -> this.stats.record(connection, player.userId(), 9, "CLASSIC", 3, false, 0, 0));

		var entries = database.read(connection -> this.stats.findByUser(connection, player.userId()));

		assertAll(
			() -> assertNull(entries.getFirst().bestTimeMs()),
			() -> assertNull(entries.getFirst().averageTimeMs())
		);
	}

	@Test
	void statsRecord_separatesSizesVariantsAndTiers() {
		Principal player = this.player("Owner");
		database.execute(connection -> {
			this.stats.record(connection, player.userId(), GridSize.NINE.n(), Variant.CLASSIC.name(), 3, true, 1000, 0);
			this.stats.record(connection, player.userId(), GridSize.NINE.n(), Variant.CHAOS.name(), 3, true, 1000, 0);
			this.stats.record(connection, player.userId(), GridSize.SIXTEEN.n(), Variant.CLASSIC.name(), 3, true, 1000, 0);
			this.stats.record(connection, player.userId(), GridSize.NINE.n(), Variant.CLASSIC.name(), 5, true, 1000, 0);
		});

		var entries = database.read(connection -> this.stats.findByUser(connection, player.userId()));
		assertEquals(4, entries.size());
	}
}
