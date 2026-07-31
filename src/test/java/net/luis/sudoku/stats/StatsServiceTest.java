package net.luis.sudoku.stats;

import net.luis.sudoku.auth.*;
import net.luis.sudoku.config.*;
import net.luis.sudoku.db.schema.Schema;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.repository.*;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.sudoku.support.PostgresTest;
import net.luis.sudoku.support.TestKeys;
import net.luis.utils.io.database.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link StatsService}, covering server-spec 9 and the rollover in 8.6.
 */
class StatsServiceTest extends PostgresTest {
	
	private static final String BOOTSTRAP = "BOOTSTRAP1";
	private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
	
	private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
	
	private StatsRepository statsRepository;
	private DailyResultRepository dailyResults;
	private DailyLeaderboardRepository leaderboard;
	private InviteRepository invites;
	private RegistrationService registrations;
	private StatsService stats;
	private ServerConfig config;
	
	private static StatsService.SyncEntry entry(int size, String variant, int difficulty, int played, int solved,
	                                            int failed, Long best, long total, int hints) {
		return new StatsService.SyncEntry(size, variant, difficulty, played, solved, failed, best, total, hints);
	}
	
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
				return StatsServiceTest.this.now.get();
			}
		};
		
		Map<String, String> env = new HashMap<>();
		env.put(EnvKeys.DB_URL, "jdbc:postgresql://db:5432/sudoku");
		env.put(EnvKeys.DB_USER, "sudoku");
		env.put(EnvKeys.DB_PASSWORD, "secret");
		env.put(EnvKeys.BOOTSTRAP_INVITE, BOOTSTRAP);
		env.put(EnvKeys.TIMEZONE, ZONE.getId());
		this.config = ServerConfig.from(Env.of(env));
		
		this.statsRepository = new StatsRepository();
		this.dailyResults = new DailyResultRepository();
		this.leaderboard = new DailyLeaderboardRepository();
		this.invites = new InviteRepository();
		UserRepository users = new UserRepository();
		DeviceRepository devices = new DeviceRepository();
		StreakRepository streaks = new StreakRepository();
		
		SessionService sessionService = new SessionService(database, new SessionRepository(), users, devices,
			new CodeGenerator(), SessionCloser.NONE);
		this.registrations = new RegistrationService(database, users, devices, this.invites, sessionService,
			new SignatureVerifier(), clock);
		this.stats = new StatsService(database, this.statsRepository, users, streaks, this.dailyResults,
			this.leaderboard, this.config, clock);
		this.registrations.ensureBootstrapInvite(BOOTSTRAP);
	}
	
	private Principal player(String name) {
		String code = BOOTSTRAP;
		if (!"Owner".equals(name)) {
			code = "INV" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
			String finalCode = code;
			database.execute(connection -> this.invites.create(connection, finalCode, null, Role.NEW, null, this.now.get()));
		}
		TestKeys keys = TestKeys.ed25519(name);
		RegistrationService.Registered registered =
			this.registrations.register(code, name, keys.publicKey(), keys.algorithm(), "Phone");
		return new Principal(registered.user(), registered.device(), registered.session());
	}
	
	// --- player browsing ---
	
	@Test
	void players_listsEveryNonRevokedPlayer() {
		this.player("Owner");
		this.player("Second");
		
		List<StatsService.PlayerSummary> players = this.stats.players();
		
		assertAll(
			() -> assertEquals(2, players.size()),
			() -> assertTrue(players.stream().anyMatch(p -> "Owner".equals(p.displayName()))),
			() -> assertTrue(players.stream().anyMatch(p -> "Second".equals(p.displayName())))
		);
	}
	
	@Test
	void players_excludesKickedPlayers() {
		this.player("Owner");
		Principal kicked = this.player("Gone");
		database.execute(connection -> new UserRepository().revoke(connection, kicked.userId()));
		
		assertEquals(1, this.stats.players().size());
	}
	
	@Test
	void players_reportsLastSeen() {
		Principal player = this.player("Owner");
		// Registration issues a session, which touches the device.
		StatsService.PlayerSummary summary = this.stats.players().stream()
			.filter(p -> p.id().equals(player.userId()))
			.findFirst()
			.orElseThrow();
		
		assertNotNull(summary.lastSeenAt());
	}
	
	// --- sync (spec 9) ---
	
	@Test
	void sync_mergesLocalHistoryIntoTheAggregates() {
		Principal player = this.player("Owner");
		
		this.stats.sync(player, List.of(
			entry(9, "CLASSIC", 3, 10, 8, 2, 45_000L, 400_000L, 3),
			entry(16, "CHAOS", 5, 2, 1, 1, 900_000L, 900_000L, 0)
		));
		
		List<StatsEntry> merged = this.stats.forUser(player.userId());
		assertAll(
			() -> assertEquals(2, merged.size()),
			() -> assertEquals(10, merged.getFirst().gamesPlayed()),
			() -> assertEquals(8, merged.getFirst().solved()),
			() -> assertEquals(45_000L, merged.getFirst().bestTimeMs()),
			() -> assertEquals(50_000L, merged.getFirst().averageTimeMs())
		);
	}
	
	@Test
	void sync_ontoExistingAggregates_addsRatherThanReplaces() {
		Principal player = this.player("Owner");
		database.execute(connection ->
			this.statsRepository.record(connection, player.userId(), 9, "CLASSIC", 3, true, 30_000, 1));
		
		this.stats.sync(player, List.of(entry(9, "CLASSIC", 3, 4, 3, 1, 45_000L, 150_000L, 2)));
		
		StatsEntry merged = this.stats.forUser(player.userId()).getFirst();
		assertAll(
			() -> assertEquals(5, merged.gamesPlayed(), "1 seeded + 4 uploaded"),
			() -> assertEquals(4, merged.solved(), "1 seeded solve + 3 uploaded"),
			() -> assertEquals(1, merged.failed(), "the seeded row was a solve, so only the upload's failure counts"),
			() -> assertEquals(30_000L, merged.bestTimeMs(), "the better of the two bests wins"),
			() -> assertEquals(3, merged.hintsUsed())
		);
	}
	
	@Test
	void sync_keepsTheExistingBestWhenTheUploadHasNone() {
		Principal player = this.player("Owner");
		database.execute(connection ->
			this.statsRepository.record(connection, player.userId(), 9, "CLASSIC", 3, true, 30_000, 0));
		
		this.stats.sync(player, List.of(entry(9, "CLASSIC", 3, 1, 0, 1, null, 0, 0)));
		
		assertEquals(30_000L, this.stats.forUser(player.userId()).getFirst().bestTimeMs());
	}
	
	@Test
	void sync_acceptsLisaAsASinglePlayerTier() {
		// Lisa never reaches multiplayer, but it is a real tier in a player's own history.
		Principal player = this.player("Owner");
		
		assertDoesNotThrow(() -> this.stats.sync(player, List.of(entry(9, "CLASSIC", 6, 1, 1, 0, 1000L, 1000L, 0))));
		assertEquals(6, this.stats.forUser(player.userId()).getFirst().difficulty());
	}
	
	@Test
	void sync_anUnsupportedSize_isRejected() {
		Principal player = this.player("Owner");
		assertThrows(ApiException.class,
			() -> this.stats.sync(player, List.of(entry(7, "CLASSIC", 3, 1, 1, 0, 1000L, 1000L, 0))));
	}
	
	@Test
	void sync_anUnknownVariant_isRejected() {
		Principal player = this.player("Owner");
		assertThrows(ApiException.class,
			() -> this.stats.sync(player, List.of(entry(9, "SPIRAL", 3, 1, 1, 0, 1000L, 1000L, 0))));
	}
	
	@Test
	void sync_negativeCounters_areRejected() {
		Principal player = this.player("Owner");
		assertThrows(ApiException.class,
			() -> this.stats.sync(player, List.of(entry(9, "CLASSIC", 3, -1, 0, 0, null, 0, 0))));
	}
	
	@Test
	void sync_moreOutcomesThanGames_isRejected() {
		Principal player = this.player("Owner");
		assertThrows(ApiException.class,
			() -> this.stats.sync(player, List.of(entry(9, "CLASSIC", 3, 1, 5, 5, null, 0, 0))));
	}
	
	@Test
	void sync_anEmptyUpload_isAccepted() {
		Principal player = this.player("Owner");
		assertEquals(0, this.stats.sync(player, List.of()));
	}
	
	// --- rollover (spec 8.6) ---
	
	@Test
	void runRollover_foldsFinishedDaysIntoStatsAndPrunesThem() {
		Principal player = this.player("Owner");
		LocalDate yesterday = LocalDate.ofInstant(NOW, ZONE).minusDays(1);
		
		database.execute(connection -> {
			this.dailyResults.insert(connection, player.userId(), yesterday, 3, 1, DailyOutcome.SOLVED, 60_000, 0, 1, true, NOW);
			this.leaderboard.record(connection, player.userId(), yesterday, 3, 60_000, 1, 1);
		});
		
		int folded = this.stats.runRollover();
		
		List<StatsEntry> aggregates = this.stats.forUser(player.userId());
		// Both source tables must be empty afterwards: they are not retained historically (spec 8.2).
		List<DailyLeaderboardRepository.Entry> remaining =
			database.read(connection -> this.leaderboard.ranking(connection, yesterday, 3));
		Long remainingResults = database.read(connection -> connection.from(Schema.DAILY_RESULTS)
			.select(Sql.count(Schema.RESULT_ID, false))
			.where(Sql.equalTo(Schema.RESULT_DATE, yesterday))
			.fetchOneOrNull());
		
		assertAll(
			() -> assertEquals(1, folded),
			() -> assertEquals(1, aggregates.size()),
			() -> assertEquals(1, aggregates.getFirst().solved()),
			() -> assertEquals(60_000L, aggregates.getFirst().bestTimeMs()),
			() -> assertEquals(1, aggregates.getFirst().hintsUsed()),
			() -> assertTrue(remaining.isEmpty(), "the leaderboard row should have been pruned"),
			() -> assertEquals(0L, remainingResults, "the daily result should have been pruned")
		);
	}
	
	@Test
	void runRollover_leavesTodayAlone() {
		Principal player = this.player("Owner");
		LocalDate today = LocalDate.ofInstant(NOW, ZONE);
		database.execute(connection ->
			this.dailyResults.insert(connection, player.userId(), today, 3, 1, DailyOutcome.SOLVED, 60_000, 0, 0, true, NOW));
		
		int folded = this.stats.runRollover();
		
		assertAll(
			() -> assertEquals(0, folded, "today is still in progress"),
			() -> assertTrue(this.stats.forUser(player.userId()).isEmpty())
		);
	}
	
	@Test
	void runRollover_skipsUnverifiedResults() {
		Principal player = this.player("Owner");
		LocalDate yesterday = LocalDate.ofInstant(NOW, ZONE).minusDays(1);
		database.execute(connection ->
			this.dailyResults.insert(connection, player.userId(), yesterday, 3, 1, DailyOutcome.SOLVED, 1, 0, 0, false, NOW));
		
		int folded = this.stats.runRollover();
		
		assertAll(
			() -> assertEquals(0, folded),
			() -> assertTrue(this.stats.forUser(player.userId()).isEmpty())
		);
	}
	
	@Test
	void runRollover_runTwice_doesNotDoubleCount() {
		Principal player = this.player("Owner");
		LocalDate yesterday = LocalDate.ofInstant(NOW, ZONE).minusDays(1);
		database.execute(connection ->
			this.dailyResults.insert(connection, player.userId(), yesterday, 3, 1, DailyOutcome.SOLVED, 60_000, 0, 0, true, NOW));
		
		this.stats.runRollover();
		int second = this.stats.runRollover();
		
		assertAll(
			() -> assertEquals(0, second, "the rows were pruned by the first pass"),
			() -> assertEquals(1, this.stats.forUser(player.userId()).getFirst().gamesPlayed())
		);
	}
	
	@Test
	void runRollover_foldsAFailedDailyAsAFailure() {
		Principal player = this.player("Owner");
		LocalDate yesterday = LocalDate.ofInstant(NOW, ZONE).minusDays(1);
		database.execute(connection ->
			this.dailyResults.insert(connection, player.userId(), yesterday, 3, 1, DailyOutcome.FAILED, 30_000, 5, 0, true, NOW));
		
		this.stats.runRollover();
		
		StatsEntry entry = this.stats.forUser(player.userId()).getFirst();
		assertAll(
			() -> assertEquals(1, entry.gamesPlayed()),
			() -> assertEquals(0, entry.solved()),
			() -> assertEquals(1, entry.failed()),
			() -> assertNull(entry.bestTimeMs(), "a failure contributes no time")
		);
	}
}
