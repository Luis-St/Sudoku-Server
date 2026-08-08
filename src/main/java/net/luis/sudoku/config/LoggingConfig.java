package net.luis.sudoku.config;

import net.luis.utils.logging.*;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Configures log4j2 at startup from {@link EnvKeys#LOG_LEVEL}, through LUtils' logging package.
 * <p>
 * There is deliberately no {@code log4j2.xml} on the classpath: a file would have to be rebuilt into
 * the image to change a level, whereas the deployment already configures everything else through the
 * environment. {@link LoggerConfiguration} builds the same appender-per-level console setup a hand
 * written configuration would, so the only thing that varies between deployments is which of those
 * levels the root logger actually references.
 * <p>
 * The threshold defaults to {@code INFO}, which is what a local run wants; the image sets
 * {@code SUDOKU_LOG_LEVEL=WARN} so production keeps only the levels that mean something went wrong.
 * <p>
 * This is the one piece of configuration that cannot be part of {@link ServerConfig}: it has to be in
 * place before the first logger is created, which is before anything else is parsed.
 */
public final class LoggingConfig {
	
	/**
	 * Every level a console appender exists for, coarsest first. Mirrors {@link LoggingType#CONSOLE}.
	 */
	private static final List<Level> CONSOLE_LEVELS = List.of(Level.FATAL, Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE);
	/**
	 * The level used when {@link EnvKeys#LOG_LEVEL} is unset, which is every run outside the container.
	 */
	public static final Level DEFAULT_LEVEL = Level.INFO;
	
	private LoggingConfig() {}
	
	/**
	 * Reads the threshold and installs the logging configuration.
	 * <p>
	 * Never throws: this runs before a logger exists, so an unusable value falls back to
	 * {@link #DEFAULT_LEVEL} and complains once logging is up, rather than killing the process with a
	 * bare stack trace on stderr. Every other configuration error still fails fast in
	 * {@link ServerConfig#fromEnvironment}.
	 *
	 * @param env the environment to read the threshold from
	 * @return the level that was actually installed
	 */
	public static @NonNull Level apply(@NonNull Env env) {
		String raw = env.optional(EnvKeys.LOG_LEVEL);
		Level level = DEFAULT_LEVEL;
		String problem = null;
		try {
			level = parse(raw);
		} catch (ConfigException e) {
			problem = e.getMessage();
		}
		
		LoggingUtils.initializeOrReconfigure(configuration(level));
		
		if (problem != null) {
			LogManager.getLogger(LoggingConfig.class).warn("{}, falling back to {}", problem, level);
		}
		return level;
	}
	
	/**
	 * Parses a level name, case insensitively. A blank or absent value means {@link #DEFAULT_LEVEL}.
	 *
	 * @throws ConfigException if the value is not a log4j2 level name
	 */
	static @NonNull Level parse(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return DEFAULT_LEVEL;
		}
		
		Level level = Level.getLevel(value.trim().toUpperCase(Locale.ROOT));
		if (level == null) {
			throw new ConfigException(EnvKeys.LOG_LEVEL + " must be one of OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL, got: " + value);
		}
		return level;
	}
	
	/**
	 * Builds the console-only configuration for a threshold.
	 * <p>
	 * Console appenders exist for every level either way, which is what lets
	 * {@link LoggingUtils#enableConsole(Level)} turn one on at runtime; the threshold only decides which
	 * of them the root logger starts out referencing. File logging stays off: the container's log is
	 * stdout, and a rolling file inside it would only ever be read by nobody.
	 *
	 * @param threshold the coarsest level to log, together with everything more severe
	 */
	static @NonNull LoggerConfiguration configuration(@NonNull Level threshold) {
		LoggerConfiguration configuration = new LoggerConfiguration("*")
			.setStatusLevel(Level.ERROR)
			.disableLogging(LoggingType.FILE);
		
		for (Level level : CONSOLE_LEVELS) {
			configuration.overrideConsolePattern(level, pattern(level));
			// Remove first either way: the defaults already contain INFO through FATAL, and adding one
			// twice would give the root logger two references to the same appender, so every line at that
			// level would print twice.
			configuration.removeDefaultLogger(LoggingType.CONSOLE, level);
			if (level.intLevel() <= threshold.intLevel()) {
				configuration.addDefaultLogger(LoggingType.CONSOLE, level);
			}
		}
		return configuration;
	}
	
	/**
	 * The pattern for a level.
	 * <p>
	 * Carries the request's {@code trace_id} and {@code source_ip}, which {@code Application}'s before
	 * filter puts in the MDC on every request and which no pattern used to read. That mattered little
	 * while production logged everything: an {@code INFO} line per request gave you the method and path
	 * anyway. At {@code WARN} that line is gone, so a warning arrives with nothing tying it to the
	 * request that caused it - and a rate-limit warning or an unverified daily result is close to
	 * useless without knowing who and from where.
	 * <p>
	 * All three of marker, trace id and source ip are usually absent - background threads have no
	 * request at all - so the {@code %replace} strips their brackets when they are empty, however many
	 * of them run together. Without it every line off a match queue would carry three empty {@code []}.
	 * The two most verbose levels name the source line, because that is what they are for.
	 */
	private static @NonNull String pattern(@NonNull Level level) {
		String levelNames = "%level{TRACE=Trace, DEBUG=Debug, INFO=Info, WARN=Warn, ERROR=Error, FATAL=Fatal}";
		String source = switch (level.name()) {
			case "TRACE" -> "%C:%line";
			case "DEBUG" -> "%C{1}:%line";
			default -> "%C{1}";
		};
		String line = "[%d{HH:mm:ss}] [%t] [%marker] [%X{trace_id}] [%X{source_ip}] [" + source + "/" + levelNames + "] %msg%n%throwable";
		// A closing bracket followed by any run of empty groups collapses back to that one bracket. The
		// repetition has to sit inside the match, after the ']', rather than wrap the whole "] []": each
		// repetition of that form would eat the ']' the next one needs to start from, so three empty
		// groups in a row would come out as one instead of none.
		// LoggerConfiguration wraps this in a %replace of its own that does the single-group version, so
		// the rendered pattern is nested - which is why LoggingConfigTest asserts on rendered output
		// rather than on the pattern text.
		return "%replace{" + line + "}{\\]( \\[\\])+}{]}";
	}
}
