package net.luis.sudoku.support;

import io.javalin.Javalin;
import net.luis.sudoku.Application;
import net.luis.sudoku.ServiceGraph;
import net.luis.sudoku.config.*;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.permission.Role;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;

/**
 * Base class for tests that drive the real HTTP surface.
 * <p>
 * Route versioning cannot be tested below the routing layer. Whether {@code /api/v1/daily} and
 * {@code /api/v2/daily} both answer, and answer differently, is a property of {@link Application#configure}
 * and of which handler method each path is bound to - a service-level test would pass with the v2 route
 * never registered at all. So this starts the actual application, on a real port, against the real
 * Postgres, and talks to it over HTTP.
 * <p>
 * A whole {@link ServiceGraph} is built per test rather than once per class, because
 * {@link PostgresTest#resetSchema} drops the schema between tests and the graph migrates it on the way up.
 */
public abstract class HttpTest extends PostgresTest {
	
	/** The bootstrap invite the first user registers with. */
	protected static final String BOOTSTRAP = "BOOTSTRAP1";
	
	protected static final ObjectMapper JSON = new ObjectMapper();
	
	private final HttpClient client = HttpClient.newHttpClient();
	private Javalin app;
	private int port;
	protected ServiceGraph services;
	
	@BeforeEach
	void startServer() {
		Map<String, String> env = new HashMap<>();
		env.put(EnvKeys.DB_URL, dataSource().getJdbcUrl());
		env.put(EnvKeys.DB_USER, dataSource().getUsername());
		env.put(EnvKeys.DB_PASSWORD, dataSource().getPassword());
		env.put(EnvKeys.BOOTSTRAP_INVITE, BOOTSTRAP);
		this.configure(env);
		
		ServerConfig config = ServerConfig.from(Env.of(env));
		this.services = new ServiceGraph(config);
		this.app = Javalin.create(javalin -> Application.configure(javalin, this.services));
		// Port 0 asks the OS for a free one, so tests never collide with each other or with a real server.
		this.app.start(0);
		this.port = this.app.port();
	}
	
	/**
	 * Hook for a subclass that needs a different server configuration - a different daily size, say.
	 *
	 * @param env The environment being assembled, already carrying the database and the bootstrap invite
	 */
	protected void configure(@NonNull Map<String, String> env) {}
	
	@AfterEach
	void stopServer() {
		if (this.app != null) {
			this.app.stop();
		}
		if (this.services != null) {
			this.services.close();
		}
	}
	
	/**
	 * Registers a player and returns their session token.
	 *
	 * @param displayName Their name; {@code "Owner"} takes the bootstrap invite, anybody else gets a fresh one
	 * @return The bearer token to authenticate with
	 */
	protected @NonNull String register(@NonNull String displayName) {
		String code = BOOTSTRAP;
		if (!"Owner".equals(displayName)) {
			code = "INV" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
			String invite = code;
			database.execute(connection -> this.services.invites().create(connection, invite, null, Role.NEW, null, Instant.now()));
		}
		
		TestKeys keys = TestKeys.ed25519(displayName);
		RegistrationService.Registered registered =
			this.services.registrationService().register(code, displayName, keys.publicKey(), keys.algorithm(), "Phone");
		return registered.session().token();
	}
	
	/**
	 * @param token The caller's bearer token
	 * @param path The path to GET, starting with a slash
	 * @return The response, its body parsed as JSON where there is one
	 */
	protected @NonNull Response get(@NonNull String token, @NonNull String path) {
		return this.send(HttpRequest.newBuilder(this.uri(path)).GET(), token);
	}
	
	/**
	 * @param token The caller's bearer token
	 * @param path The path to POST to, starting with a slash
	 * @param body The request body as JSON
	 * @return The response, its body parsed as JSON where there is one
	 */
	protected @NonNull Response post(@NonNull String token, @NonNull String path, @NonNull String body) {
		return this.send(HttpRequest.newBuilder(this.uri(path)).POST(HttpRequest.BodyPublishers.ofString(body)), token);
	}
	
	/**
	 * @param token The caller's bearer token
	 * @param path The path to PUT to, starting with a slash
	 * @param body The request body as JSON
	 * @return The response, its body parsed as JSON where there is one
	 */
	protected @NonNull Response put(@NonNull String token, @NonNull String path, @NonNull String body) {
		return this.send(HttpRequest.newBuilder(this.uri(path)).PUT(HttpRequest.BodyPublishers.ofString(body)), token);
	}
	
	private @NonNull URI uri(@NonNull String path) {
		return URI.create("http://localhost:" + this.port + path);
	}
	
	private @NonNull Response send(HttpRequest.@NonNull Builder builder, @NonNull String token) {
		HttpRequest request = builder
			.header("Authorization", "Bearer " + token)
			.header("Content-Type", "application/json")
			.build();
		
		try {
			HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
			return new Response(response.statusCode(), response.body());
		} catch (IOException e) {
			throw new IllegalStateException("Request to " + request.uri() + " failed", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted waiting for " + request.uri(), e);
		}
	}
	
	/**
	 * One HTTP response.
	 *
	 * @param status the status code
	 * @param body the raw body, which is empty for a 204
	 */
	protected record Response(int status, @NonNull String body) {
		
		/**
		 * @return The body as JSON
		 * @throws IllegalStateException If the body is empty
		 */
		public @NonNull JsonNode json() {
			if (this.body.isBlank()) {
				throw new IllegalStateException("Response " + this.status + " carried no body");
			}
			return JSON.readTree(this.body);
		}
		
		/**
		 * @param path The field names to walk, in order
		 * @return The node at that path, or null if any step is missing
		 */
		public @Nullable JsonNode at(@NonNull String @NonNull ... path) {
			JsonNode node = this.json();
			for (String name : path) {
				node = node.get(name);
				if (node == null) {
					return null;
				}
			}
			return node;
		}
	}
}
