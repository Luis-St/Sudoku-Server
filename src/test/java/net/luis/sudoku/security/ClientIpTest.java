package net.luis.sudoku.security;

import org.junit.jupiter.api.Test;

import static net.luis.sudoku.security.ClientIp.resolve;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test class for {@link ClientIp}.
 * <p>
 * The server runs plain HTTP behind a TLS-terminating proxy, so getting this wrong collapses every
 * per-IP rate-limit bucket onto the proxy's own address.
 */
class ClientIpTest {

	private static final String PROXY = "127.0.0.1";
	private static final String CLIENT = "203.0.113.7";

	@Test
	void resolve_withNoProxyHeaders_fallsBackToTheSocketPeer() {
		assertEquals(PROXY, resolve(null, null, PROXY, true));
	}

	@Test
	void resolve_withTrustDisabled_ignoresProxyHeadersEntirely() {
		// Directly exposed: the headers are attacker-controlled, so believing them would hand out a
		// free rate-limit bypass.
		assertEquals(PROXY, resolve("1.2.3.4", "5.6.7.8", PROXY, false));
	}

	@Test
	void resolve_prefersXRealIp_becauseNginxOverwritesIt() {
		assertEquals(CLIENT, resolve(CLIENT, null, PROXY, true));
	}

	@Test
	void resolve_xRealIpWinsOverForwardedFor() {
		assertEquals(CLIENT, resolve(CLIENT, "9.9.9.9", PROXY, true));
	}

	@Test
	void resolve_withASingleForwardedForEntry_usesIt() {
		assertEquals(CLIENT, resolve(null, CLIENT, PROXY, true));
	}

	@Test
	void resolve_withASpoofedForwardedFor_usesTheLastEntryNotTheFirst() {
		// nginx's $proxy_add_x_forwarded_for APPENDS the real peer to whatever the client sent, so a
		// client sending "1.2.3.4" produces "1.2.3.4, <real>". Taking the first entry - the common
		// mistake - would let anyone forge their rate-limit identity.
		assertEquals(CLIENT, resolve(null, "1.2.3.4, " + CLIENT, PROXY, true));
	}

	@Test
	void resolve_withSeveralProxyHops_usesTheOutermostTrustedEntry() {
		assertEquals(CLIENT, resolve(null, "1.2.3.4, 10.0.0.1, " + CLIENT, PROXY, true));
	}

	@Test
	void resolve_withBlankHeaders_fallsBackRatherThanReturningEmpty() {
		assertEquals(PROXY, resolve("   ", "", PROXY, true));
	}

	@Test
	void resolve_trimsSurroundingWhitespace() {
		assertEquals(CLIENT, resolve(null, "1.2.3.4,  " + CLIENT + "  ", PROXY, true));
	}
}
