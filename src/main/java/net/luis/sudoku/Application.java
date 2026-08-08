package net.luis.sudoku;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import net.luis.sudoku.config.*;
import net.luis.sudoku.error.ErrorHandlerConfig;
import net.luis.sudoku.handler.*;
import net.luis.sudoku.security.ClientIp;
import net.luis.sudoku.version.GenVersion;
import net.luis.utils.logging.LoggingExceptionHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.UUID;

public class Application {
	
	/**
	 * Log4j2's own logger rather than SLF4J, because this is the only class that needs {@code FATAL}:
	 * everything it logs at that level is a failure to start, which SLF4J cannot express. Every other
	 * class logs through SLF4J as before - both ends up in the same log4j2 configuration.
	 */
	private static final Logger log;
	
	static void main() {
		// Housekeeping, match queues, the puzzle refill pool and both shutdown hooks all run on threads
		// nobody catches for. Without this, a thread dying takes its stack trace to stderr, which in a
		// warn-only production log is the one place nobody is looking.
		Thread.setDefaultUncaughtExceptionHandler(new LoggingExceptionHandler());
		
		ServerConfig config;
		try {
			config = ServerConfig.fromEnvironment();
		} catch (ConfigException e) {
			// Fatal, not error: the process is about to stop existing, and this is the whole reason why.
			log.fatal("Configuration error: {}", e.getMessage());
			System.exit(1);
			return;
		}
		
		ServiceGraph services;
		try {
			services = new ServiceGraph(config);
		} catch (RuntimeException e) {
			// An unreachable database, a failed migration or a rejected bootstrap invite all land here.
			// Previously they escaped main as a bare stack trace with no log line at all, so a container
			// that never came up left nothing behind that said why.
			log.fatal("Failed to start: the service graph could not be built", e);
			System.exit(1);
			return;
		}
		Runtime.getRuntime().addShutdownHook(new Thread(services::close, "services-shutdown"));
		
		log.info("Server id {} (daily {}x{} {}, rollover zone {})", services.serverId(), config.dailySize().n(), config.dailySize().n(), config.dailyVariant(), config.timezone().getId());
		log.info("shared-core genVersion {}, API version {}", GenVersion.CURRENT, ApiVersion.CURRENT);
		
		Javalin app = Javalin.create(javalin -> configure(javalin, services));
		Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "javalin-shutdown"));
		
		try {
			app.start(config.port());
		} catch (RuntimeException e) {
			// Almost always the port already being in use, which is worth naming rather than leaving to a
			// Jetty stack trace.
			log.fatal("Failed to start: port {} could not be bound", config.port(), e);
			System.exit(1);
			return;
		}
		log.info("{} started on port {}", config.serverName(), config.port());
	}
	
	/**
	 * Registers plugins, filters and every route. Kept separate from {@link #main} so integration tests
	 * can build the same application against a test {@link ServiceGraph}.
	 */
	static void configure(@NonNull JavalinConfig javalin, @NonNull ServiceGraph services) {
		javalin.startup.showJavalinBanner = false;
		javalin.http.defaultContentType = "application/json";
		
		// Jetty closes an idle WebSocket after 30 seconds by default, which is *shorter than a player
		// thinking about a cell*: a co-op or race match sends nothing at all while nobody is typing, so a
		// perfectly healthy connection was being dropped mid-match and reported to the other side as a
		// disconnect. Clients ping every MatchSocketHandler.CLIENT_PING_SECONDS, so this only has to be a
		// comfortable multiple of that - long enough to survive a few missed pings on mobile, short enough
		// that a genuinely dead peer is still noticed inside the reconnect grace window.
		javalin.jetty.modifyWebSocketServletFactory(factory -> {
			factory.setIdleTimeout(Duration.ofSeconds(MatchSocketHandler.SOCKET_IDLE_TIMEOUT_SECONDS));
			factory.setMaxTextMessageSize(MatchSocketHandler.MAX_FRAME_BYTES);
		});
		
		javalin.registerPlugin(new OpenApiPlugin(pluginConfig ->
			pluginConfig.withDefinitionConfiguration((_, definition) ->
				definition.info(info ->
					info.title("Sudoku Server").version("1.0.0").description("Sudoku puzzle generation and solving API")
				)
			)
		));
		javalin.registerPlugin(new SwaggerPlugin());
		
		ErrorHandlerConfig.register(javalin);
		
		javalin.routes.before(ctx -> {
			String traceId = UUID.randomUUID().toString();
			MDC.put("trace_id", traceId);
			MDC.put("source_ip", ClientIp.of(ctx, services.config().trustProxy()));
			ctx.attribute("trace_id", traceId);
		});
		
		javalin.routes.after(ctx -> {
			log.info("{} {} {}", ctx.method(), ctx.path(), ctx.status());
			MDC.clear();
		});
		
		var healthHandler = new HealthHandler(services.schemaVersion(), services.matchRegistry()::activeCount);
		var serverInfoHandler = new ServerInfoHandler(services.config(), services.serverId());
		var registerHandler = new RegisterHandler(services.registrationService(), services.rateLimiter(), services.config().trustProxy());
		var authHandler = new AuthHandler(services.challengeService(), services.rateLimiter(), services.config().trustProxy());
		var userHandler = new UserHandler(services.authentication(), services.userAdminService());
		var inviteHandler = new InviteHandler(services.authentication(), services.inviteService());
		var deviceHandler = new DeviceHandler(services.authentication(), services.deviceLinkService(), services.userAdminService(), services.rateLimiter(), services.config().trustProxy());
		var recoveryHandler = new RecoveryHandler(services.authentication(), services.recoveryService(), services.rateLimiter(), services.config().trustProxy());
		var dailyHandler = new DailyHandler(services.authentication(), services.dailyService());
		var statsHandler = new StatsHandler(services.authentication(), services.statsService(), services.presenceService());
		var currencyHandler = new CurrencyHandler(services.authentication(), services.currencyService());
		var matchHandler = new MatchHandler(services.authentication(), services.matchService(), services.presenceService(), services.rateLimiter());
		var presenceHandler = new PresenceHandler(services.authentication(), services.presenceService(), services.config().presence());
		var matchSocketHandler = new MatchSocketHandler(services.authentication(), services.sessionService(), services.matchService(), services.rateLimiter(), services.clock());
		
		// Health
		javalin.routes.get("/health", healthHandler::health);
		
		// Server
		javalin.routes.get("/api/v1/server-info", serverInfoHandler::serverInfo);
		
		// Registration and authentication
		javalin.routes.post("/api/v1/register", registerHandler::register);
		javalin.routes.post("/api/v1/auth/challenge", authHandler::challenge);
		javalin.routes.post("/api/v1/auth/verify", authHandler::verify);
		
		// Users and roles
		javalin.routes.get("/api/v1/users", userHandler::list);
		javalin.routes.get("/api/v1/users/me", userHandler::me);
		javalin.routes.patch("/api/v1/users/{id}/role", userHandler::changeRole);
		javalin.routes.delete("/api/v1/users/{id}", userHandler::kick);
		javalin.routes.post("/api/v1/users/{id}/reinstate", userHandler::reinstate);
		
		// Invites
		javalin.routes.post("/api/v1/invites", inviteHandler::create);
		javalin.routes.get("/api/v1/invites", inviteHandler::list);
		javalin.routes.delete("/api/v1/invites/{code}", inviteHandler::revoke);
		
		// Devices
		javalin.routes.post("/api/v1/devices/link-code", deviceHandler::createLinkCode);
		javalin.routes.post("/api/v1/devices/link", deviceHandler::link);
		javalin.routes.get("/api/v1/devices", deviceHandler::list);
		javalin.routes.delete("/api/v1/devices/{id}", deviceHandler::revoke);
		
		// Account recovery
		javalin.routes.post("/api/v1/users/me/email", recoveryHandler::requestEmailVerification);
		javalin.routes.post("/api/v1/users/me/email/verify", recoveryHandler::confirmEmail);
		javalin.routes.post("/api/v1/auth/recovery/request", recoveryHandler::requestRecovery);
		javalin.routes.post("/api/v1/auth/recovery/redeem", recoveryHandler::redeemRecovery);
		
		// Daily puzzle
		javalin.routes.get("/api/v1/daily", dailyHandler::daily);
		javalin.routes.get("/api/v1/daily/leaderboard", dailyHandler::leaderboard);
		javalin.routes.post("/api/v1/daily/result", dailyHandler::submitResult);
		javalin.routes.get("/api/v1/daily/streak", dailyHandler::streak);
		javalin.routes.post("/api/v1/daily/streak/sync", dailyHandler::syncStreak);
		javalin.routes.post("/api/v1/daily/streak/restore", dailyHandler::restoreStreak);
		javalin.routes.get("/api/v1/preferences", dailyHandler::preferences);
		javalin.routes.put("/api/v1/preferences", dailyHandler::setPreferences);
		
		// Players and statistics
		javalin.routes.get("/api/v1/players", statsHandler::players);
		javalin.routes.get("/api/v1/players/{id}/stats", statsHandler::playerStats);
		javalin.routes.post("/api/v1/stats/sync", statsHandler::sync);
		javalin.routes.post("/api/v1/stats/games", statsHandler::recordGames);
		
		// Currency
		javalin.routes.get("/api/v1/currency", currencyHandler::balance);
		javalin.routes.post("/api/v1/currency/sync", currencyHandler::sync);
		
		// Matches
		javalin.routes.post("/api/v1/matches", matchHandler::create);
		// Joining needs only the code, which is why this hangs off /matches rather than /matches/{id}/join -
		// there is no id to put there until the code has been resolved. The older two-value route below stays
		// registered for clients already in the wild.
		javalin.routes.post("/api/v1/matches/join", matchHandler::joinByCode);
		// Before the {id} route: "active" is a name, not a match id, and a path parameter would swallow it.
		javalin.routes.get("/api/v1/matches/active", matchHandler::active);
		javalin.routes.get("/api/v1/matches/{id}", matchHandler::get);
		javalin.routes.delete("/api/v1/matches/{id}", matchHandler::cancel);
		javalin.routes.post("/api/v1/matches/{id}/join", matchHandler::join);
		javalin.routes.post("/api/v1/matches/{id}/invite", matchHandler::invite);
		javalin.routes.post("/api/v1/matches/{id}/request", matchHandler::request);
		javalin.routes.post("/api/v1/matches/{id}/resign", matchHandler::resign);
		javalin.routes.ws("/ws/v1/matches/{id}", matchSocketHandler);
		
		// Presence: the heartbeat every signed-in client sends, and the match requests it collects. There is no
		// presence WebSocket - an open socket is a worse answer to "is this player there" than a recent
		// timestamp; see PresenceService.
		javalin.routes.post("/api/v1/presence/heartbeat", presenceHandler::heartbeat);
		javalin.routes.post("/api/v1/presence/offline", presenceHandler::offline);
		javalin.routes.delete("/api/v1/match-requests/{id}", presenceHandler::dismissRequest);
	}
	
	static {
		// Before the first logger exists anywhere, including the static fields of everything the graph
		// constructs. Reading one variable here rather than in ServerConfig is deliberate: a configuration
		// error has to be loggable, so logging cannot wait for configuration to parse.
		LoggingConfig.apply(Env.ofSystem());
		log = LogManager.getLogger(Application.class);
	}
}
