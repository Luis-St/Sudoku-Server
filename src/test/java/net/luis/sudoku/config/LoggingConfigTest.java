package net.luis.sudoku.config;

import net.luis.utils.logging.LoggerConfiguration;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link LoggingConfig}.
 */
class LoggingConfigTest {

	/**
	 * @return the names of the appenders the root logger references for a threshold, which is what decides
	 *   the levels that actually reach the console
	 */
	private static Set<String> rootAppendersFor(Level threshold) {
		Configuration configuration = LoggingConfig.configuration(threshold).build();
		LoggerConfig root = configuration.getRootLogger();
		return root.getAppenderRefs().stream().map(reference -> reference.getRef()).collect(Collectors.toSet());
	}

	/**
	 * Formats one event through the console layout for a level, with {@code context} standing in for the
	 * MDC the before filter populates.
	 *
	 * @return the line as it would reach stdout
	 */
	private static String render(Level level, Map<String, String> context) {
		Configuration configuration = LoggingConfig.configuration(Level.TRACE).build();
		Appender appender = configuration.getAppenders().values().stream()
			.filter(candidate -> candidate.getName().toLowerCase().contains(level.name().toLowerCase()))
			.findFirst()
			.orElseThrow();

		LogEvent event = Log4jLogEvent.newBuilder()
			.setLoggerName("net.luis.sudoku.Example")
			.setLevel(level)
			.setMessage(new SimpleMessage("something happened"))
			.setContextData(new SortedArrayStringMap(context))
			.setThreadName("main")
			.build();
		return ((PatternLayout) appender.getLayout()).toSerializable(event);
	}

	private static boolean logs(Level threshold, Level level) {
		return rootAppendersFor(threshold).stream().anyMatch(name -> name.toLowerCase().contains(level.name().toLowerCase()));
	}

	@Test
	void parse_valueUnset_fallsBackToTheDefaultLevel() {
		assertEquals(LoggingConfig.DEFAULT_LEVEL, LoggingConfig.parse(null));
		assertEquals(LoggingConfig.DEFAULT_LEVEL, LoggingConfig.parse(""));
		assertEquals(LoggingConfig.DEFAULT_LEVEL, LoggingConfig.parse("   "));
	}

	@Test
	void parse_levelName_isCaseAndWhitespaceInsensitive() {
		assertEquals(Level.WARN, LoggingConfig.parse("WARN"));
		assertEquals(Level.WARN, LoggingConfig.parse("warn"));
		assertEquals(Level.WARN, LoggingConfig.parse("  WaRn  "));
	}

	@Test
	void parse_everySupportedLevel_isAccepted() {
		for (Level level : List.of(Level.OFF, Level.FATAL, Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE, Level.ALL)) {
			assertEquals(level, LoggingConfig.parse(level.name()));
		}
	}

	@Test
	void parse_unknownLevelName_fails() {
		ConfigException exception = assertThrows(ConfigException.class, () -> LoggingConfig.parse("verbose"));
		assertTrue(exception.getMessage().contains(EnvKeys.LOG_LEVEL));
		assertTrue(exception.getMessage().contains("verbose"));
	}

	@Test
	void configuration_warnThreshold_keepsOnlyWarnAndAbove() {
		assertTrue(logs(Level.WARN, Level.FATAL));
		assertTrue(logs(Level.WARN, Level.ERROR));
		assertTrue(logs(Level.WARN, Level.WARN));
		assertFalse(logs(Level.WARN, Level.INFO));
		assertFalse(logs(Level.WARN, Level.DEBUG));
		assertFalse(logs(Level.WARN, Level.TRACE));
	}

	@Test
	void configuration_infoThreshold_matchesTheLevelsTheServerLoggedBefore() {
		assertTrue(logs(Level.INFO, Level.FATAL));
		assertTrue(logs(Level.INFO, Level.ERROR));
		assertTrue(logs(Level.INFO, Level.WARN));
		assertTrue(logs(Level.INFO, Level.INFO));
		assertFalse(logs(Level.INFO, Level.DEBUG));
		assertFalse(logs(Level.INFO, Level.TRACE));
	}

	@Test
	void configuration_traceThreshold_keepsEveryLevel() {
		assertEquals(6, rootAppendersFor(Level.TRACE).size());
	}

	@Test
	void configuration_offThreshold_keepsNothing() {
		assertTrue(rootAppendersFor(Level.OFF).isEmpty());
	}

	/**
	 * The defaults of {@link LoggerConfiguration} already contain INFO through FATAL, so adding one of them
	 * again would give the root logger two references to the same appender and print every line twice.
	 */
	@Test
	void configuration_defaultLevels_areNotReferencedTwice() {
		Configuration configuration = LoggingConfig.configuration(Level.INFO).build();
		List<String> references = configuration.getRootLogger().getAppenderRefs().stream().map(reference -> reference.getRef()).toList();
		assertEquals(references.size(), Set.copyOf(references).size());
	}

	/**
	 * The MDC keys {@code Application}'s before filter sets have to actually appear somewhere, or a warning
	 * arrives with nothing tying it to the request that caused it.
	 */
	@Test
	void configuration_everyPattern_carriesTheRequestContext() {
		Configuration configuration = LoggingConfig.configuration(Level.TRACE).build();
		configuration.getAppenders().values().forEach(appender -> {
			String pattern = ((PatternLayout) appender.getLayout()).getConversionPattern();
			assertTrue(pattern.contains("%X{trace_id}"), pattern);
			assertTrue(pattern.contains("%X{source_ip}"), pattern);
		});
	}

	/**
	 * Marker, trace id and source ip are all usually absent at once - a line off a match queue has none of
	 * the three - and a run of empty {@code []} would be most of the line.
	 * <p>
	 * Rendered rather than asserted against the pattern text, because the pattern is not the whole story:
	 * {@link LoggerConfiguration} wraps every override in a {@code %replace} of its own, so what actually
	 * runs is nested and only the output says whether the two agree.
	 */
	@Test
	void render_absentContextValues_leaveNoEmptyBrackets() {
		assertFalse(render(Level.WARN, Map.of()).contains("[]"));
		assertFalse(render(Level.WARN, Map.of("trace_id", "trace-1")).contains("[]"));
		assertFalse(render(Level.WARN, Map.of("trace_id", "trace-1", "source_ip", "203.0.113.7")).contains("[]"));
	}

	@Test
	void render_presentContextValues_areCarriedIntoTheLine() {
		String line = render(Level.WARN, Map.of("trace_id", "trace-1", "source_ip", "203.0.113.7"));
		assertTrue(line.contains("trace-1"), line);
		assertTrue(line.contains("203.0.113.7"), line);
		assertTrue(line.contains("something happened"), line);
	}

	@Test
	void configuration_anyThreshold_writesToTheConsoleOnly() {
		Configuration configuration = LoggingConfig.configuration(Level.WARN).build();
		configuration.getAppenders().values().forEach(appender -> assertEquals("Console", appender.getClass().getSimpleName().replace("Appender", "")));
	}
}
