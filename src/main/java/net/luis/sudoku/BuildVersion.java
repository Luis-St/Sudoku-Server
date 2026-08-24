package net.luis.sudoku;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Which build of this server is running, as {@code GET /health} reports it (server-spec 14).
 * <p>
 * Distinct from {@link ApiVersion}, which is the contract clients speak, and from the schema version,
 * which is the state of the database: this one identifies the <em>binary</em>, and it is the field that
 * answers "is the fix actually deployed?" during an incident. Nothing else on {@code /health} can say so -
 * a container serving the previous jar reports the same status, the same uptime once it has been up a
 * while, and the same schema version whenever the deploy carried no migration.
 * <p>
 * Read from a resource the build writes ({@code generateVersionResource} in {@code build.gradle.kts}),
 * because the version is declared in the build script and a Java constant repeating it is a second place
 * to bump and therefore a place to be wrong.
 */
public final class BuildVersion {

	private static final Logger log = LoggerFactory.getLogger(BuildVersion.class);

	/** What is reported when the resource is missing, which means running from classes the build did not produce. */
	public static final String UNKNOWN = "unknown";

	private static final String RESOURCE = "/net/luis/sudoku/version.properties";

	/** The version this jar was built at, or {@link #UNKNOWN}. */
	public static final String CURRENT = read();

	private BuildVersion() {}

	private static @NonNull String read() {
		try (InputStream stream = BuildVersion.class.getResourceAsStream(RESOURCE)) {
			if (stream == null) {
				// Not worth failing a boot over: an unknown version costs one field on /health, while a
				// server that refuses to start costs the whole deployment.
				log.warn("No {} on the classpath, /health will report the version as {}", RESOURCE, UNKNOWN);
				return UNKNOWN;
			}
			Properties properties = new Properties();
			properties.load(stream);
			String version = properties.getProperty("version");
			return version == null || version.isBlank() ? UNKNOWN : version.strip();
		} catch (IOException e) {
			log.warn("Failed to read {}, /health will report the version as {}", RESOURCE, UNKNOWN, e);
			return UNKNOWN;
		}
	}
}
