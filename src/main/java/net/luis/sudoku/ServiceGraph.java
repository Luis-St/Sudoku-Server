package net.luis.sudoku;

import net.luis.sudoku.auth.*;
import net.luis.sudoku.config.ServerConfig;
import net.luis.sudoku.currency.CurrencyService;
import net.luis.sudoku.daily.DailyService;
import net.luis.sudoku.db.*;
import net.luis.sudoku.device.DeviceLinkService;
import net.luis.sudoku.invite.InviteService;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.mail.MailService;
import net.luis.sudoku.match.MatchRegistry;
import net.luis.sudoku.match.MatchService;
import net.luis.sudoku.permission.UserAdminService;
import net.luis.sudoku.presence.PresenceService;
import net.luis.sudoku.puzzle.PuzzleQueue;
import net.luis.sudoku.recovery.RecoveryService;
import net.luis.sudoku.repository.*;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.sudoku.security.RateLimiter;
import net.luis.sudoku.stats.StatsService;
import net.luis.utils.io.database.Sql;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.concurrent.*;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Constructs and owns every long-lived collaborator.
 * <p>
 * Hand-wired rather than injected by a container: the graph is small, the construction order is
 * meaningful (migrations before anything reads a table), and being able to read the whole application's
 * shape in one file is worth more here than the annotations would save.
 */
public final class ServiceGraph implements AutoCloseable {
	
	private static final Logger log = LoggerFactory.getLogger(ServiceGraph.class);
	
	private final ServerConfig config;
	private final Database database;
	private final int schemaVersion;
	private final String serverId;
	
	private final UserRepository users = new UserRepository();
	private final DeviceRepository devices = new DeviceRepository();
	private final InviteRepository invites = new InviteRepository();
	private final SessionRepository sessions = new SessionRepository();
	private final AuthChallengeRepository challenges = new AuthChallengeRepository();
	private final LinkCodeRepository linkCodes = new LinkCodeRepository();
	private final EmailVerificationRepository emailVerifications = new EmailVerificationRepository();
	private final RecoveryCodeRepository recoveryCodes = new RecoveryCodeRepository();
	private final PreferenceRepository preferences = new PreferenceRepository();
	private final DailyResultRepository dailyResults = new DailyResultRepository();
	private final StreakRepository streaks = new StreakRepository();
	private final DailyLeaderboardRepository dailyLeaderboard = new DailyLeaderboardRepository();
	private final StatsRepository stats = new StatsRepository();
	private final CurrencyLedgerRepository ledger = new CurrencyLedgerRepository();
	private final MatchRepository matchRepository = new MatchRepository();
	
	private final Clock clock;
	private final CodeGenerator codes = new CodeGenerator();
	private final SignatureVerifier signatureVerifier = new SignatureVerifier();
	private final RateLimiter rateLimiter;
	private final SessionService sessionService;
	private final Authentication authentication;
	private final ChallengeService challengeService;
	private final RegistrationService registrationService;
	private final InviteService inviteService;
	private final UserAdminService userAdminService;
	private final DeviceLinkService deviceLinkService;
	private final MailService mailService;
	private final RecoveryService recoveryService;
	private final DailyService dailyService;
	private final StatsService statsService;
	private final CurrencyService currencyService;
	private final MatchRegistry matchRegistry = new MatchRegistry();
	private final PresenceService presenceService = new PresenceService();
	private final MatchService matchService;
	private final PuzzleQueue puzzleQueue;
	
	private final ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform().name("housekeeping").daemon().unstarted(runnable));
	
	public ServiceGraph(@NonNull ServerConfig config) {
		this(config, Clock.systemUTC());
	}
	
	public ServiceGraph(@NonNull ServerConfig config, @NonNull Clock clock) {
		this.config = config;
		this.clock = clock;
		
		this.database = new Database(DataSourceFactory.createDatabase(config.database()));
		this.schemaVersion = Migrations.migrate(this.database);
		this.serverId = new ServerMetaRepository(this.database).serverId();
		
		this.rateLimiter = new RateLimiter(clock);
		// The registry is the socket closer: this is what makes SESSION_SUPERSEDED (spec 6.2) and a kick
		// (spec 7.2) take effect on a live connection rather than only in the database.
		SessionCloser closer = this.matchRegistry::closeSocketsFor;
		this.sessionService = new SessionService(this.database, this.sessions, this.users, this.devices, this.codes, closer);
		this.authentication = new Authentication(this.sessionService, clock);
		this.challengeService = new ChallengeService(this.database, this.challenges, this.devices, this.users, this.sessionService, this.signatureVerifier, this.codes, clock);
		this.registrationService = new RegistrationService(this.database, this.users, this.devices, this.invites, this.sessionService, this.signatureVerifier, clock);
		
		this.inviteService = new InviteService(this.database, this.invites, this.codes, clock);
		this.userAdminService = new UserAdminService(this.database, this.users, this.devices, this.sessions, closer);
		
		this.deviceLinkService = new DeviceLinkService(this.database, this.linkCodes, this.devices, this.users, this.sessionService, this.signatureVerifier, this.codes, clock);
		
		this.mailService = new MailService(config.mail());
		this.recoveryService = new RecoveryService(this.database, this.emailVerifications, this.recoveryCodes, this.users, this.devices, this.sessionService, this.signatureVerifier, this.codes, this.mailService, clock);
		
		this.statsService = new StatsService(this.database, this.stats, this.users, this.streaks, this.dailyResults, this.dailyLeaderboard, config, clock);
		this.currencyService = new CurrencyService(this.database, this.ledger, this.stats, config, clock);
		this.dailyService = new DailyService(this.database, config, this.serverId, this.preferences, this.dailyResults, this.streaks, this.dailyLeaderboard, this.currencyService, this.statsService, clock);
		this.puzzleQueue = new PuzzleQueue(this::activePlayerCount);
		
		this.matchService = new MatchService(this.database, this.matchRepository, this.matchRegistry, this.puzzleQueue, this.currencyService, config, this.codes, clock);
		
		this.registrationService.ensureBootstrapInvite(config.bootstrapInvite());
		// Live board state is memory-resident, so anything left running is wreckage: abandon and refund
		// (spec 9a.3).
		this.matchService.recoverAfterRestart();
		this.startHousekeeping();
	}
	
	private void startHousekeeping() {
		this.housekeeping.scheduleAtFixedRate(() -> {
			try {
				this.rateLimiter.sweep();
				this.database.execute(connection -> {
					this.sessions.deleteExpired(connection, this.clock.instant());
					this.challenges.deleteExpired(connection, this.clock.instant());
					this.emailVerifications.deleteExpired(connection, this.clock.instant());
					this.recoveryCodes.deleteExpired(connection, this.clock.instant());
				});
			} catch (RuntimeException e) {
				// A failed sweep must never kill the scheduled task, or the leak it prevents comes back.
				log.warn("Housekeeping pass failed", e);
			}
		}, 5, 5, TimeUnit.MINUTES);
	}
	
	public @NonNull ServerConfig config() {
		return this.config;
	}
	
	public @NonNull Database database() {
		return this.database;
	}
	
	public int schemaVersion() {
		return this.schemaVersion;
	}
	
	public @NonNull String serverId() {
		return this.serverId;
	}
	
	public @NonNull Clock clock() {
		return this.clock;
	}
	
	public @NonNull UserRepository users() {
		return this.users;
	}
	
	public @NonNull DeviceRepository devices() {
		return this.devices;
	}
	
	public @NonNull InviteRepository invites() {
		return this.invites;
	}
	
	public @NonNull SessionRepository sessions() {
		return this.sessions;
	}
	
	public @NonNull CodeGenerator codes() {
		return this.codes;
	}
	
	public @NonNull RateLimiter rateLimiter() {
		return this.rateLimiter;
	}
	
	public @NonNull SessionService sessionService() {
		return this.sessionService;
	}
	
	public @NonNull Authentication authentication() {
		return this.authentication;
	}
	
	public @NonNull ChallengeService challengeService() {
		return this.challengeService;
	}
	
	public @NonNull AuthChallengeRepository challenges() {
		return this.challenges;
	}
	
	public @NonNull SignatureVerifier signatureVerifier() {
		return this.signatureVerifier;
	}
	
	public @NonNull RegistrationService registrationService() {
		return this.registrationService;
	}
	
	public @NonNull InviteService inviteService() {
		return this.inviteService;
	}
	
	public @NonNull UserAdminService userAdminService() {
		return this.userAdminService;
	}
	
	public @NonNull DeviceLinkService deviceLinkService() {
		return this.deviceLinkService;
	}
	
	public @NonNull MailService mailService() {
		return this.mailService;
	}
	
	public @NonNull RecoveryService recoveryService() {
		return this.recoveryService;
	}
	
	public @NonNull EmailVerificationRepository emailVerifications() {
		return this.emailVerifications;
	}
	
	public @NonNull RecoveryCodeRepository recoveryCodes() {
		return this.recoveryCodes;
	}
	
	public @NonNull DailyService dailyService() {
		return this.dailyService;
	}
	
	public @NonNull PuzzleQueue puzzleQueue() {
		return this.puzzleQueue;
	}
	
	public @NonNull StatsService statsService() {
		return this.statsService;
	}
	
	public @NonNull CurrencyService currencyService() {
		return this.currencyService;
	}
	
	public @NonNull MatchService matchService() {
		return this.matchService;
	}
	
	public @NonNull PresenceService presenceService() {
		return this.presenceService;
	}
	
	public @NonNull MatchRegistry matchRegistry() {
		return this.matchRegistry;
	}
	
	public @NonNull MatchRepository matchRepository() {
		return this.matchRepository;
	}
	
	public @NonNull StatsRepository stats() {
		return this.stats;
	}
	
	public @NonNull CurrencyLedgerRepository ledger() {
		return this.ledger;
	}
	
	public @NonNull PreferenceRepository preferences() {
		return this.preferences;
	}
	
	public @NonNull DailyResultRepository dailyResults() {
		return this.dailyResults;
	}
	
	public @NonNull StreakRepository streaks() {
		return this.streaks;
	}
	
	public @NonNull DailyLeaderboardRepository dailyLeaderboard() {
		return this.dailyLeaderboard;
	}
	
	/**
	 * @return how many players currently hold a session, which is what the puzzle queue sizes itself
	 *   against: each bucket targets twice this number
	 */
	private int activePlayerCount() {
		try {
			return this.database.read(transaction -> transaction.from(SESSIONS)
				.select(Sql.count(SESSION_TOKEN, false))
				.where(Sql.greaterThan(SESSION_EXPIRES_AT, this.clock.instant()))
				.fetchOne().intValue());
		} catch (RuntimeException e) {
			// The queue is a latency optimisation; a failed count must never break generation.
			log.debug("Could not count active players", e);
			return 0;
		}
	}
	
	public @NonNull LinkCodeRepository linkCodes() {
		return this.linkCodes;
	}
	
	@Override
	public void close() {
		this.housekeeping.shutdownNow();
		// Spec 14: close match sockets with SERVER_SHUTDOWN before the pool goes away.
		this.matchRegistry.close();
		this.puzzleQueue.close();
		this.database.close();
	}
}
