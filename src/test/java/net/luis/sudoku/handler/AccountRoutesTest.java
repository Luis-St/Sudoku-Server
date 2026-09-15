package net.luis.sudoku.handler;

import net.luis.sudoku.device.DeviceLinkService;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.support.HttpTest;
import net.luis.sudoku.support.TestKeys;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@code GET /users/me}, and specifically for the forced-resync flag it carries
 * (server-spec 7.3).
 * <p>
 * Driven over HTTP because the flag's whole point is what a <em>client</em> can see: the column is on
 * {@code devices}, the route is on the user, and the bridge between them is the principal the bearer token
 * resolves to. A service-level test would prove nothing about which device's row is read.
 * <p>
 * The flag is raised with a hand-written {@code UPDATE}, which is not a shortcut - it is the only way it
 * is ever raised. There is deliberately no route, service or repository method that sets it, so a test
 * that used one would be testing something the production code does not have.
 */
class AccountRoutesTest extends HttpTest {
	
	private void setForceUpdate(@NonNull String deviceLabel, boolean value) {
		database.withConnection(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("UPDATE devices SET force_update = ? WHERE label = ?")) {
				statement.setBoolean(1, value);
				statement.setString(2, deviceLabel);
				assertEquals(1, statement.executeUpdate());
			}
			return null;
		});
	}
	
	@Test
	void meReportsNoForcedUpdateByDefault() {
		String token = this.register("Owner");
		
		Response response = this.get(token, "/api/v1/users/me");
		
		assertEquals(200, response.status());
		assertFalse(response.at("forceUpdate").asBoolean());
	}
	
	@Test
	void meReportsAForcedUpdateSetInSql() {
		String token = this.register("Owner");
		this.setForceUpdate("Phone", true);
		
		assertTrue(this.get(token, "/api/v1/users/me").at("forceUpdate").asBoolean());
	}
	
	/**
	 * Reading the flag must not consume it. The operator raised it and the operator lowers it - a client
	 * that crashes halfway through adopting the server's state has to be told again on its next poll, and
	 * an application that cleared the column on delivery would have thrown that instruction away.
	 */
	@Test
	void readingTheFlagDoesNotClearIt() {
		String token = this.register("Owner");
		this.setForceUpdate("Phone", true);
		
		assertTrue(this.get(token, "/api/v1/users/me").at("forceUpdate").asBoolean());
		assertTrue(this.get(token, "/api/v1/users/me").at("forceUpdate").asBoolean());
		assertTrue(this.get(token, "/api/v1/users/me").at("forceUpdate").asBoolean());
	}
	
	@Test
	void loweringTheFlagInSqlStopsReportingIt() {
		String token = this.register("Owner");
		this.setForceUpdate("Phone", true);
		assertTrue(this.get(token, "/api/v1/users/me").at("forceUpdate").asBoolean());
		
		this.setForceUpdate("Phone", false);
		
		assertFalse(this.get(token, "/api/v1/users/me").at("forceUpdate").asBoolean());
	}
	
	/**
	 * The flag is aimed at one client, not at an account: a player whose phone has drifted must not have
	 * their tablet reset along with it.
	 */
	@Test
	void theFlagIsPerDeviceNotPerUser() {
		String phoneToken = this.register("Owner");
		String tabletToken = this.linkSecondDevice(phoneToken);
		
		this.setForceUpdate("Phone", true);
		
		assertTrue(this.get(phoneToken, "/api/v1/users/me").at("forceUpdate").asBoolean());
		assertFalse(this.get(tabletToken, "/api/v1/users/me").at("forceUpdate").asBoolean());
	}
	
	/** Links a second device to the account behind {@code token} and returns its own session token. */
	private @NonNull String linkSecondDevice(@NonNull String token) {
		Principal owner = this.services.sessionService().authenticate(token, this.services.clock().instant());
		String code = this.services.deviceLinkService().mint(owner).code();
		TestKeys keys = TestKeys.ed25519("Owner-tablet");
		DeviceLinkService.Linked linked = this.services.deviceLinkService().link(code, keys.publicKey(), keys.algorithm(), "Tablet");
		return linked.session().token();
	}
}
