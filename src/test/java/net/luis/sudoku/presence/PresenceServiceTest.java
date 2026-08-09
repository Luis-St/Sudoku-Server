package net.luis.sudoku.presence;

import net.luis.sudoku.auth.*;
import net.luis.sudoku.config.PresenceConfig;
import net.luis.sudoku.db.schema.Schema;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.invite.RegistrationService;
import net.luis.sudoku.match.*;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.repository.*;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.sudoku.support.PostgresTest;
import net.luis.sudoku.support.TestKeys;
import net.luis.utils.io.database.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link PresenceService} - online status derived from heartbeat age, and the stored match
 * requests that replaced the presence socket's push.
 * <p>
 * Against a real Postgres because the whole point of the design is that presence is database state: a
 * substitute that kept it in a map would be testing the implementation this replaced.
 */
class PresenceServiceTest extends PostgresTest {
	
	private static final String BOOTSTRAP = "BOOTSTRAP1";
	private static final Instant NOW = Instant.parse("2026-07-31T10:00:00Z");
	private static final int ONLINE_TTL_SECONDS = 30;
	private static final int REQUEST_TTL_SECONDS = 60;
	
	private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
	
	private InviteRepository invites;
	private RegistrationService registrations;
	private MatchRepository matches;
	private MatchRequestRepository requests;
	private PresenceService presence;
	
	@BeforeEach
	void createServices() {
		this.now.set(NOW);
		Clock clock = new Clock() {
			
			@Override
			public ZoneId getZone() {
				return ZoneId.of("UTC");
			}
			
			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}
			
			@Override
			public Instant instant() {
				return PresenceServiceTest.this.now.get();
			}
		};
		
		UserRepository users = new UserRepository();
		DeviceRepository devices = new DeviceRepository();
		this.invites = new InviteRepository();
		this.matches = new MatchRepository();
		this.requests = new MatchRequestRepository();
		
		SessionService sessions = new SessionService(database, new SessionRepository(), users, devices, new CodeGenerator(), SessionCloser.NONE);
		this.registrations = new RegistrationService(database, users, devices, this.invites, sessions, new SignatureVerifier(), clock);
		this.presence = new PresenceService(database, new PresenceRepository(), this.requests,
			new PresenceConfig(ONLINE_TTL_SECONDS, REQUEST_TTL_SECONDS), clock);
		this.registrations.ensureBootstrapInvite(BOOTSTRAP);
	}
	
	private void advance(int seconds) {
		this.now.set(this.now.get().plus(Duration.ofSeconds(seconds)));
	}
	
	private Principal player(String name) {
		String code = BOOTSTRAP;
		if (!"Owner".equals(name)) {
			code = "INV" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
			String finalCode = code;
			database.execute(transaction -> this.invites.create(transaction, finalCode, null, Role.NEW, null, this.now.get()));
		}
		TestKeys keys = TestKeys.ed25519(name);
		RegistrationService.Registered registered = this.registrations.register(code, name, keys.publicKey(), keys.algorithm(), "Phone");
		return new Principal(registered.user(), registered.device(), registered.session());
	}
	
	/** A match row straight through the repository - nothing here needs a live match, only a joinable one. */
	private Match match(Principal creator, int stake) {
		return database.transaction(transaction -> this.matches.create(transaction, MatchMode.RACE, creator.userId(),
			GridSize.FOUR, Variant.CLASSIC, Difficulty.TWO, 42L, "givens", false, true, stake, "TOKEN-" + UUID.randomUUID(), this.now.get()));
	}
	
	// --- online status ---
	
	@Test
	void nobodyIsOnlineWithoutAHeartbeat() {
		Principal alice = this.player("Owner");
		
		assertAll(
			() -> assertFalse(this.presence.isOnline(alice.userId())),
			() -> assertTrue(this.presence.onlineUsers().isEmpty())
		);
	}
	
	@Test
	void heartbeatMakesOnlyTheCallerOnline() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		
		this.presence.heartbeat(alice.userId());
		
		assertAll(
			() -> assertTrue(this.presence.isOnline(alice.userId())),
			() -> assertFalse(this.presence.isOnline(bob.userId())),
			() -> assertEquals(Set.of(alice.userId()), this.presence.onlineUsers())
		);
	}
	
	@Test
	void aHeartbeatRightOnTheTtlBoundaryStillCounts() {
		Principal alice = this.player("Owner");
		this.presence.heartbeat(alice.userId());
		
		this.advance(ONLINE_TTL_SECONDS);
		
		// The threshold is inclusive: at exactly the TTL the last heartbeat is still the one keeping them up,
		// and rounding this the other way would drop a player one tick early on every single beat.
		assertTrue(this.presence.isOnline(alice.userId()));
	}
	
	@Test
	void onlineLapsesOnceTheHeartbeatGoesStale() {
		Principal alice = this.player("Owner");
		this.presence.heartbeat(alice.userId());
		
		this.advance(ONLINE_TTL_SECONDS + 1);
		
		// The entire reason presence moved into a table: nothing had to notice Alice was gone. She stopped
		// heartbeating and went offline on her own, with no close frame, no timeout and no cooperation.
		assertAll(
			() -> assertFalse(this.presence.isOnline(alice.userId())),
			() -> assertTrue(this.presence.onlineUsers().isEmpty())
		);
	}
	
	@Test
	void repeatedHeartbeatsKeepAPlayerOnlineIndefinitely() {
		Principal alice = this.player("Owner");
		
		for (int beat = 0; beat < 5; beat++) {
			this.presence.heartbeat(alice.userId());
			this.advance(10);
			assertTrue(this.presence.isOnline(alice.userId()), "a player beating every 10s must never flicker offline");
		}
	}
	
	@Test
	void goOfflineTakesEffectImmediatelyRatherThanAtTheTtl() {
		Principal alice = this.player("Owner");
		this.presence.heartbeat(alice.userId());
		
		this.presence.goOffline(alice.userId());
		
		assertFalse(this.presence.isOnline(alice.userId()));
	}
	
	@Test
	void goOfflineForSomebodyWhoWasNeverOnlineIsANoOp() {
		Principal alice = this.player("Owner");
		
		assertDoesNotThrow(() -> this.presence.goOffline(alice.userId()));
		assertFalse(this.presence.isOnline(alice.userId()));
	}
	
	// --- match requests ---
	
	@Test
	void aRequestReachesTheTargetOnTheirNextHeartbeat() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		this.presence.heartbeat(bob.userId());
		Match match = this.match(alice, 7);
		
		this.presence.requestMatch(bob.userId(), match, alice.userId());
		List<PendingMatchRequest> pending = this.presence.heartbeat(bob.userId());
		
		PendingMatchRequest request = pending.getFirst();
		assertAll(
			() -> assertEquals(1, pending.size()),
			() -> assertEquals(match.id(), request.matchId()),
			// Read off the match, never copied at request time.
			() -> assertEquals(match.inviteToken(), request.inviteToken()),
			() -> assertEquals(MatchMode.RACE, request.mode()),
			() -> assertEquals(7, request.stake()),
			() -> assertEquals(alice.userId(), request.fromUserId()),
			() -> assertEquals("Owner", request.fromDisplayName())
		);
	}
	
	@Test
	void aRequestGoesToItsTargetAndNobodyElse() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		this.presence.heartbeat(bob.userId());
		
		this.presence.requestMatch(bob.userId(), this.match(alice, 0), alice.userId());
		
		assertTrue(this.presence.heartbeat(alice.userId()).isEmpty());
	}
	
	@Test
	void anOfflinePlayerCannotBeAsked() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		Match match = this.match(alice, 0);
		
		ApiException e = assertThrows(ApiException.class, () -> this.presence.requestMatch(bob.userId(), match, alice.userId()));
		
		assertEquals(ErrorCode.PLAYER_OFFLINE, e.code());
	}
	
	@Test
	void aPlayerWhoseHeartbeatWentStaleCannotBeAsked() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		this.presence.heartbeat(bob.userId());
		Match match = this.match(alice, 0);
		
		this.advance(ONLINE_TTL_SECONDS + 1);
		
		ApiException e = assertThrows(ApiException.class, () -> this.presence.requestMatch(bob.userId(), match, alice.userId()));
		assertEquals(ErrorCode.PLAYER_OFFLINE, e.code());
	}
	
	@Test
	void askingTwiceForTheSameMatchReplacesTheEarlierRequest() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		this.presence.heartbeat(bob.userId());
		Match match = this.match(alice, 0);
		
		this.presence.requestMatch(bob.userId(), match, alice.userId());
		this.presence.requestMatch(bob.userId(), match, alice.userId());
		
		// Two banners for one match would be a bug the invitee has to dismiss twice.
		assertEquals(1, this.presence.heartbeat(bob.userId()).size());
	}
	
	@Test
	void aRequestIsNotConsumedByBeingRead() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		this.presence.heartbeat(bob.userId());
		this.presence.requestMatch(bob.userId(), this.match(alice, 0), alice.userId());
		
		UUID first = this.presence.heartbeat(bob.userId()).getFirst().id();
		this.advance(10);
		List<PendingMatchRequest> second = this.presence.heartbeat(bob.userId());
		
		// A client killed between receiving a request and showing it must not lose it, so the row survives
		// being read and the same id comes back until it is dismissed or expires.
		assertAll(
			() -> assertEquals(1, second.size()),
			() -> assertEquals(first, second.getFirst().id())
		);
	}
	
	@Test
	void anExpiredRequestIsNotServed() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		this.presence.heartbeat(bob.userId());
		this.presence.requestMatch(bob.userId(), this.match(alice, 0), alice.userId());
		
		this.advance(REQUEST_TTL_SECONDS + 1);
		
		assertTrue(this.presence.heartbeat(bob.userId()).isEmpty());
	}
	
	@Test
	void aRequestForAMatchThatHasMovedOnIsNotServed() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		this.presence.heartbeat(bob.userId());
		Match match = this.match(alice, 0);
		this.presence.requestMatch(bob.userId(), match, alice.userId());
		
		database.execute(transaction -> this.matches.markEnded(transaction, match.id(), MatchState.ABANDONED, null,
			EndReason.SERVER_RESTART, this.now.get()));
		
		// The row is still there and unexpired, but there is nothing left to join - handing it to a client
		// would offer them a match that cannot be entered.
		assertTrue(this.presence.heartbeat(bob.userId()).isEmpty());
	}
	
	@Test
	void deletingTheMatchTakesItsRequestsWithIt() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		this.presence.heartbeat(bob.userId());
		Match match = this.match(alice, 0);
		this.presence.requestMatch(bob.userId(), match, alice.userId());
		
		database.execute(transaction -> transaction.from(Schema.MATCHES).delete()
			.where(Sql.equalTo(Schema.MATCH_ID, match.id())).execute());
		
		assertTrue(this.presence.heartbeat(bob.userId()).isEmpty());
	}
	
	// --- dismissal ---
	
	@Test
	void dismissRemovesTheRequest() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		this.presence.heartbeat(bob.userId());
		this.presence.requestMatch(bob.userId(), this.match(alice, 0), alice.userId());
		UUID requestId = this.presence.heartbeat(bob.userId()).getFirst().id();
		
		this.presence.dismissRequest(bob.userId(), requestId);
		
		assertTrue(this.presence.heartbeat(bob.userId()).isEmpty());
	}
	
	@Test
	void dismissingAnAlreadyGoneRequestSucceeds() {
		Principal bob = this.player("Owner");
		
		// A client retrying a dismissal it is not sure landed must not be told it failed for having worked.
		assertDoesNotThrow(() -> this.presence.dismissRequest(bob.userId(), UUID.randomUUID()));
	}
	
	@Test
	void onlyTheInvitedPlayerMayDismissARequest() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		Principal carol = this.player("Carol");
		this.presence.heartbeat(bob.userId());
		this.presence.requestMatch(bob.userId(), this.match(alice, 0), alice.userId());
		UUID requestId = this.presence.heartbeat(bob.userId()).getFirst().id();
		
		ApiException e = assertThrows(ApiException.class, () -> this.presence.dismissRequest(carol.userId(), requestId));
		
		assertAll(
			() -> assertEquals(ErrorCode.FORBIDDEN, e.code()),
			() -> assertEquals(1, this.presence.heartbeat(bob.userId()).size(), "the refused dismissal must not have deleted it")
		);
	}
	
	// --- housekeeping ---
	
	@Test
	void sweepDropsExpiredRequestsAndLeavesLiveOnesAlone() {
		Principal alice = this.player("Owner");
		Principal bob = this.player("Bob");
		this.presence.heartbeat(bob.userId());
		this.presence.requestMatch(bob.userId(), this.match(alice, 0), alice.userId());
		
		this.advance(REQUEST_TTL_SECONDS + 1);
		this.presence.heartbeat(bob.userId());
		this.presence.requestMatch(bob.userId(), this.match(alice, 0), alice.userId());
		this.presence.sweep();
		
		List<PendingMatchRequest> remaining = database.read(transaction -> this.requests.findPending(transaction, bob.userId(), this.now.get()));
		assertEquals(1, remaining.size());
	}
	
	@Test
	void sweepKeepsAHeartbeatThatIsMerelyOffline() {
		Principal alice = this.player("Owner");
		this.presence.heartbeat(alice.userId());
		
		this.advance(ONLINE_TTL_SECONDS + 1);
		this.presence.sweep();
		this.presence.heartbeat(alice.userId());
		
		// Pruning is about table size, never about the answer: a player who has been offline for a minute is
		// online again the moment they beat, whether or not a sweep happened in between.
		assertTrue(this.presence.isOnline(alice.userId()));
	}
	
	@Test
	void sweepDropsHeartbeatsTooOldToEverBeOnlineAgain() {
		Principal alice = this.player("Owner");
		this.presence.heartbeat(alice.userId());
		
		this.advance((int) Duration.ofHours(2).toSeconds());
		this.presence.sweep();
		
		int rows = database.read(transaction -> transaction.from(Schema.PRESENCE).select().fetch().size());
		assertEquals(0, rows);
	}
}
