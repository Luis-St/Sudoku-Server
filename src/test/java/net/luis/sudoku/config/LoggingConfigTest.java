package net.luis.sudoku.config;

import net.luis.utils.logging.LoggerConfiguration;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
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

	@Test
	void configuration_anyThreshold_writesToTheConsoleOnly() {
		Configuration configuration = LoggingConfig.configuration(Level.WARN).build();
		configuration.getAppenders().values().forEach(appender -> assertEquals("Console", appender.getClass().getSimpleName().replace("Appender", "")));
	}
}
