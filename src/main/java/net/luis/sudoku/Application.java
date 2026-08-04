package net.luis.sudoku;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import net.luis.sudoku.config.ConfigException;
import net.luis.sudoku.config.ServerConfig;
import net.luis.sudoku.error.ErrorHandlerConfig;
import net.luis.sudoku.handler.*;
import net.luis.sudoku.security.ClientIp;
import net.luis.sudoku.version.GenVersion;
import org.jspecify.annotations.NonNull;
import org.slf4j.*;

import java.util.UUID;

public class Application {
	
	private static final Logger log = LoggerFactory.getLogger(Application.class);
	
	static void main() {
		ServerConfig config;
		try {
			config = ServerConfig.fromEnvironment();
		} catch (ConfigException e) {
			// Nothing is up yet, so log4j's own startup noise would bury this; make it unmissable.
			log.error("Configuration error: {}", e.getMessage());
			System.exit(1);
			return;
		}
		
		ServiceGraph services = new ServiceGraph(config);
		Runtime.getRuntime().addShutdownHook(new Thread(services::close, "services-shutdown"));
		
		log.info("Server id {} (daily {}x{} {}, rollover zone {})", services.serverId(), config.dailySize().n(), config.dailySize().n(), config.dailyVariant(), config.timezone().getId());
		log.info("shared-core genVersion {}, API version {}", GenVersion.CURRENT, ApiVersion.CURRENT);
		
		Javalin app = Javalin.create(javalin -> configure(javalin, services));
		Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "javalin-shutdown"));
		
		app.start(config.port());
		log.info("{} started on port {}", config.serverName(), config.port());
	}
	
	/**
	 * Registers plugins, filters and every route. Kept separate from {@link #main} so integration tests
	 * can build the same application against a test {@link ServiceGraph}.
	 */
	static void configure(@NonNull JavalinConfig javalin, @NonNull ServiceGraph services) {
		javalin.startup.showJavalinBanner = false;
		javalin.http.defaultContentType = "application/json";
		
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
		var matchHandler = new MatchHandler(services.authentication(), services.matchService(), services.presenceService());
		var presenceHandler = new PresenceHandler(services.authentication(), services.presenceService(), services.config().presence());
		var matchSocketHandler = new MatchSocketHandler(services.authentication(), services.sessionService(), services.matchService(), services.rateLimiter(), services.clock());
		
		// Health
		javalin.routes.get("/health", healthHandler::health);
		
		// Server
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/server-info", serverInfoHandler::serverInfo);
		
		// Registration and authentication
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/register", registerHandler::register);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/auth/challenge", authHandler::challenge);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/auth/verify", authHandler::verify);
		
		// Users and roles
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/users", userHandler::list);
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/users/me", userHandler::me);
		javalin.routes.patch(ApiVersion.PATH_PREFIX + "/users/{id}/role", userHandler::changeRole);
		javalin.routes.delete(ApiVersion.PATH_PREFIX + "/users/{id}", userHandler::kick);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/users/{id}/reinstate", userHandler::reinstate);
		
		// Invites
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/invites", inviteHandler::create);
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/invites", inviteHandler::list);
		javalin.routes.delete(ApiVersion.PATH_PREFIX + "/invites/{code}", inviteHandler::revoke);
		
		// Devices
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/devices/link-code", deviceHandler::createLinkCode);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/devices/link", deviceHandler::link);
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/devices", deviceHandler::list);
		javalin.routes.delete(ApiVersion.PATH_PREFIX + "/devices/{id}", deviceHandler::revoke);
		
		// Account recovery
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/users/me/email", recoveryHandler::requestEmailVerification);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/users/me/email/verify", recoveryHandler::confirmEmail);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/auth/recovery/request", recoveryHandler::requestRecovery);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/auth/recovery/redeem", recoveryHandler::redeemRecovery);
		
		// Daily puzzle
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/daily", dailyHandler::daily);
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/daily/leaderboard", dailyHandler::leaderboard);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/daily/result", dailyHandler::submitResult);
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/daily/streak", dailyHandler::streak);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/daily/streak/restore", dailyHandler::restoreStreak);
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/preferences", dailyHandler::preferences);
		javalin.routes.put(ApiVersion.PATH_PREFIX + "/preferences", dailyHandler::setPreferences);
		
		// Players and statistics
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/players", statsHandler::players);
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/players/{id}/stats", statsHandler::playerStats);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/stats/sync", statsHandler::sync);
		
		// Currency
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/currency", currencyHandler::balance);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/currency/sync", currencyHandler::sync);
		
		// Matches
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/matches", matchHandler::create);
		javalin.routes.get(ApiVersion.PATH_PREFIX + "/matches/{id}", matchHandler::get);
		javalin.routes.delete(ApiVersion.PATH_PREFIX + "/matches/{id}", matchHandler::cancel);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/matches/{id}/join", matchHandler::join);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/matches/{id}/invite", matchHandler::invite);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/matches/{id}/request", matchHandler::request);
		javalin.routes.ws(ApiVersion.WS_PATH_PREFIX + "/matches/{id}", matchSocketHandler);

		// Presence: the heartbeat every signed-in client sends, and the match requests it collects. There is no
		// presence WebSocket - an open socket is a worse answer to "is this player there" than a recent
		// timestamp; see PresenceService.
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/presence/heartbeat", presenceHandler::heartbeat);
		javalin.routes.post(ApiVersion.PATH_PREFIX + "/presence/offline", presenceHandler::offline);
		javalin.routes.delete(ApiVersion.PATH_PREFIX + "/match-requests/{id}", presenceHandler::dismissRequest);
	}
}
