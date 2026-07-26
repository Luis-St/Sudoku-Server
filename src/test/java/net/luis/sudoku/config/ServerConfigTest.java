package net.luis.sudoku.config;

import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link ServerConfig}.
 */
class ServerConfigTest {

	/**
	 * @return an environment holding exactly the four required variables and nothing else
	 */
	private static Map<String, String> minimalEnv() {
		Map<String, String> env = new HashMap<>();
		env.put(EnvKeys.DB_URL, "jdbc:postgresql://db:5432/sudoku");
		env.put(EnvKeys.DB_USER, "sudoku");
		env.put(EnvKeys.DB_PASSWORD, "secret");
		env.put(EnvKeys.BOOTSTRAP_INVITE, "bootstrap-code");
		return env;
	}

	private static ServerConfig configWith(String key, String value) {
		Map<String, String> env = minimalEnv();
		env.put(key, value);
		return ServerConfig.from(Env.of(env));
	}

	private static ConfigException failWith(String key, String value) {
		Map<String, String> env = minimalEnv();
		env.put(key, value);
		return assertThrows(ConfigException.class, () -> ServerConfig.from(Env.of(env)));
	}

	@Test
	void from_onlyRequiredVariablesSet_appliesEverySpecifiedDefault() {
		ServerConfig config = ServerConfig.from(Env.of(minimalEnv()));

		assertAll(
			() -> assertEquals(7000, config.port()),
			() -> assertEquals("Sudoku Server", config.serverName()),
			() -> assertEquals(ZoneId.of("UTC"), config.timezone()),
			() -> assertEquals(GridSize.NINE, config.dailySize()),
			() -> assertEquals(Variant.CLASSIC, config.dailyVariant()),
			() -> assertEquals(10, config.database().poolSize()),
			() -> assertTrue(config.trustProxy(), "the documented deployment is always behind a proxy"),
			() -> assertEquals(10, config.currencyDailyGameCap())
		);
	}

	@Test
	void from_onlyRequiredVariablesSet_appliesEveryDuelAndMatchDefault() {
		ServerConfig config = ServerConfig.from(Env.of(minimalEnv()));
		DuelConfig duel = config.duel();

		assertAll(
			() -> assertEquals(90, duel.initialBank()),
			() -> assertEquals(6, duel.gainPerCorrect()),
			() -> assertEquals(20, duel.lossPerIncorrect()),
			() -> assertEquals(180, duel.maxBank()),
			() -> assertEquals(10, duel.minTurn()),
			() -> assertEquals(0.5, duel.regenRatio()),
			() -> assertEquals(40, duel.maxHandovers()),
			() -> assertEquals(60, config.match().reconnectGraceSeconds()),
			() -> assertEquals(3, config.match().reconnectLimit())
		);
	}

	@Test
	void from_requiredVariableMissing_throwsNamingIt() {
		for (String required : new String[] { EnvKeys.DB_URL, EnvKeys.DB_USER, EnvKeys.DB_PASSWORD, EnvKeys.BOOTSTRAP_INVITE }) {
			Map<String, String> env = minimalEnv();
			env.remove(required);

			ConfigException e = assertThrows(ConfigException.class, () -> ServerConfig.from(Env.of(env)),
				"expected " + required + " to be required");
			assertTrue(e.getMessage().contains(required), e.getMessage());
		}
	}

	@Test
	void from_requiredVariableBlank_isTreatedAsMissing() {
		// An empty value in a compose file is nearly always an unset secret, not an intentional value.
		ConfigException e = failWith(EnvKeys.DB_PASSWORD, "   ");
		assertTrue(e.getMessage().contains(EnvKeys.DB_PASSWORD), e.getMessage());
	}

	@Test
	void from_allOptionalVariablesSet_parsesEachOne() {
		Map<String, String> env = minimalEnv();
		env.put(EnvKeys.PORT, "8080");
		env.put(EnvKeys.SERVER_NAME, "Friends Server");
		env.put(EnvKeys.TIMEZONE, "Europe/Berlin");
		env.put(EnvKeys.DB_POOL_SIZE, "20");
		env.put(EnvKeys.DAILY_SIZE, "16");
		env.put(EnvKeys.CURRENCY_DAILY_GAME_CAP, "5");

		ServerConfig config = ServerConfig.from(Env.of(env));

		assertAll(
			() -> assertEquals(8080, config.port()),
			() -> assertEquals("Friends Server", config.serverName()),
			() -> assertEquals(ZoneId.of("Europe/Berlin"), config.timezone()),
			() -> assertEquals(20, config.database().poolSize()),
			() -> assertEquals(GridSize.SIXTEEN, config.dailySize()),
			() -> assertEquals(5, config.currencyDailyGameCap())
		);
	}

	@Test
	void from_nonNumericInteger_throws() {
		ConfigException e = failWith(EnvKeys.PORT, "not-a-port");
		assertTrue(e.getMessage().contains(EnvKeys.PORT), e.getMessage());
	}

	@Test
	void from_portOutOfRange_throws() {
		assertAll(
			() -> assertThrows(ConfigException.class, () -> configWith(EnvKeys.PORT, "0")),
			() -> assertThrows(ConfigException.class, () -> configWith(EnvKeys.PORT, "65536"))
		);
	}

	@Test
	void from_unknownTimezone_throws() {
		ConfigException e = failWith(EnvKeys.TIMEZONE, "Mars/Olympus_Mons");
		assertTrue(e.getMessage().contains(EnvKeys.TIMEZONE), e.getMessage());
	}

	@Test
	void from_unsupportedDailySize_throws() {
		// 7 is not a grid edge length shared-core knows about.
		ConfigException e = failWith(EnvKeys.DAILY_SIZE, "7");
		assertTrue(e.getMessage().contains(EnvKeys.DAILY_SIZE), e.getMessage());
	}

	@Test
	void from_everySupportedDailySize_isAccepted() {
		assertAll(
			() -> assertEquals(GridSize.FOUR, configWith(EnvKeys.DAILY_SIZE, "4").dailySize()),
			() -> assertEquals(GridSize.SIX, configWith(EnvKeys.DAILY_SIZE, "6").dailySize()),
			() -> assertEquals(GridSize.NINE, configWith(EnvKeys.DAILY_SIZE, "9").dailySize()),
			() -> assertEquals(GridSize.TWELVE, configWith(EnvKeys.DAILY_SIZE, "12").dailySize()),
			() -> assertEquals(GridSize.SIXTEEN, configWith(EnvKeys.DAILY_SIZE, "16").dailySize())
		);
	}

	@Test
	void from_poolSizeBelowOne_throws() {
		assertThrows(ConfigException.class, () -> configWith(EnvKeys.DB_POOL_SIZE, "0"));
	}

	@Test
	void from_negativeCurrencyCap_throws() {
		assertThrows(ConfigException.class, () -> configWith(EnvKeys.CURRENCY_DAILY_GAME_CAP, "-1"));
	}




	@Test
	void trustProxy_canBeDisabledForADirectlyExposedServer() {
		// Off, the proxy headers are client-controlled and must be ignored.
		assertFalse(configWith(EnvKeys.TRUST_PROXY, "false").trustProxy());
		assertTrue(configWith(EnvKeys.TRUST_PROXY, "true").trustProxy());
	}

	@Test
	void from_aNonBooleanTrustProxy_throws() {
		ConfigException e = failWith(EnvKeys.TRUST_PROXY, "maybe");
		assertTrue(e.getMessage().contains(EnvKeys.TRUST_PROXY), e.getMessage());
	}

	@Test
	void from_regenRatioOutsideUnitInterval_throws() {
		assertAll(
			() -> assertThrows(ConfigException.class, () -> configWith(EnvKeys.DUEL_REGEN_RATIO, "-0.1")),
			() -> assertThrows(ConfigException.class, () -> configWith(EnvKeys.DUEL_REGEN_RATIO, "1.1"))
		);
	}

	@Test
	void from_initialBankAboveMaxBank_throws() {
		Map<String, String> env = minimalEnv();
		env.put(EnvKeys.DUEL_INITIAL_BANK, "200");
		env.put(EnvKeys.DUEL_MAX_BANK, "180");

		ConfigException e = assertThrows(ConfigException.class, () -> ServerConfig.from(Env.of(env)));
		assertTrue(e.getMessage().contains(EnvKeys.DUEL_INITIAL_BANK), e.getMessage());
	}

	@Test
	void from_nonPositiveDuelValue_throws() {
		assertAll(
			() -> assertThrows(ConfigException.class, () -> configWith(EnvKeys.DUEL_GAIN_CORRECT, "0")),
			() -> assertThrows(ConfigException.class, () -> configWith(EnvKeys.DUEL_LOSS_INCORRECT, "0")),
			() -> assertThrows(ConfigException.class, () -> configWith(EnvKeys.DUEL_MIN_TURN, "0")),
			() -> assertThrows(ConfigException.class, () -> configWith(EnvKeys.DUEL_MAX_HANDOVERS, "0"))
		);
	}

	@Test
	void from_negativeReconnectLimit_throws() {
		assertThrows(ConfigException.class, () -> configWith(EnvKeys.MATCH_RECONNECT_LIMIT, "-1"));
	}

	@Test
	void dailyVariant_theFixedVariant_isAlwaysClassic() {
		// The daily is always classic; it is deliberately not configurable (spec 3).
		assertEquals(Variant.CLASSIC, ServerConfig.from(Env.of(minimalEnv())).dailyVariant());
	}

	@Test
	void safeUrl_urlWithoutCredentials_isReturnedUnchanged() {
		DatabaseConfig database = ServerConfig.from(Env.of(minimalEnv())).database();
		assertEquals("jdbc:postgresql://db:5432/sudoku", database.safeUrl());
	}

	@Test
	void safeUrl_urlWithEmbeddedCredentials_masksThem() {
		DatabaseConfig database = configWith(EnvKeys.DB_URL, "jdbc:postgresql://user:pw@db:5432/sudoku").database();
		assertEquals("jdbc:postgresql://***@db:5432/sudoku", database.safeUrl());
	}
}
