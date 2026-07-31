package net.luis.sudoku.presence;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link PresenceService} - the online set and the push channel match requests travel on.
 */
class PresenceServiceTest {
	
	private static final UUID ALICE = UUID.randomUUID();
	private static final UUID BOB = UUID.randomUUID();
	
	private PresenceService presence;
	
	@BeforeEach
	void createService() {
		this.presence = new PresenceService();
	}
	
	@Test
	void isOnlineIsFalseWithoutAConnection() {
		assertFalse(this.presence.isOnline(ALICE));
		assertTrue(this.presence.onlineUsers().isEmpty());
	}
	
	@Test
	void registerMakesAUserOnline() {
		FakePresenceConnection connection = new FakePresenceConnection();
		this.presence.register(ALICE, connection);
		
		assertTrue(this.presence.isOnline(ALICE));
		assertEquals(java.util.Set.of(ALICE), this.presence.onlineUsers());
	}
	
	@Test
	void registerSendsTheOnlineSetToEveryConnection() {
		FakePresenceConnection alice = new FakePresenceConnection();
		FakePresenceConnection bob = new FakePresenceConnection();
		this.presence.register(ALICE, alice);
		alice.clear();
		
		this.presence.register(BOB, bob);
		
		// Alice must learn that Bob arrived without asking - live online status is the whole point.
		assertTrue(alice.received().stream().anyMatch(message -> message.type().equals(PresenceMessage.Type.ONLINE.name())));
		Object ids = bob.received().getFirst().payloadOrEmpty().get("userIds");
		assertInstanceOf(List.class, ids);
		assertTrue(((List<?>) ids).containsAll(List.of(ALICE.toString(), BOB.toString())));
	}
	
	@Test
	void unregisterDropsTheUserOnlyOnceTheirLastConnectionIsGone() {
		FakePresenceConnection phone = new FakePresenceConnection();
		FakePresenceConnection tablet = new FakePresenceConnection();
		this.presence.register(ALICE, phone);
		this.presence.register(ALICE, tablet);
		
		this.presence.unregister(ALICE, phone);
		assertTrue(this.presence.isOnline(ALICE), "a second device still holds the user online");
		
		this.presence.unregister(ALICE, tablet);
		assertFalse(this.presence.isOnline(ALICE));
	}
	
	@Test
	void unregisteringAnUnknownUserIsANoOp() {
		assertDoesNotThrow(() -> this.presence.unregister(ALICE, new FakePresenceConnection()));
		assertFalse(this.presence.isOnline(ALICE));
	}
	
	@Test
	void sendDeliversToTheAddressedUserOnly() {
		FakePresenceConnection alice = new FakePresenceConnection();
		FakePresenceConnection bob = new FakePresenceConnection();
		this.presence.register(ALICE, alice);
		this.presence.register(BOB, bob);
		alice.clear();
		bob.clear();
		
		boolean delivered = this.presence.send(BOB, PresenceMessage.of(PresenceMessage.Type.MATCH_REQUEST, java.util.Map.of("matchId", "m")));
		
		assertTrue(delivered);
		assertEquals(1, bob.received().size());
		assertEquals(PresenceMessage.Type.MATCH_REQUEST.name(), bob.received().getFirst().type());
		assertTrue(alice.received().isEmpty());
	}
	
	@Test
	void sendToAnOfflineUserReportsUndelivered() {
		assertFalse(this.presence.send(ALICE, PresenceMessage.of(PresenceMessage.Type.MATCH_REQUEST, java.util.Map.of())));
	}
	
	@Test
	void sendReportsUndeliveredWhenEveryConnectionIsClosed() {
		FakePresenceConnection closed = new FakePresenceConnection();
		this.presence.register(ALICE, closed);
		closed.close();
		
		// The map still holds the socket - onClose has not fired yet - but nothing can reach the player,
		// which the caller must be able to tell apart from a successful push.
		assertFalse(this.presence.send(ALICE, PresenceMessage.of(PresenceMessage.Type.MATCH_REQUEST, java.util.Map.of())));
	}
	
	/** Records what the server pushed, standing in for a Javalin socket. */
	private static final class FakePresenceConnection implements PresenceConnection {
		
		private final List<PresenceMessage> received = new CopyOnWriteArrayList<>();
		
		private boolean open = true;
		
		private @NonNull List<PresenceMessage> received() {
			return List.copyOf(this.received);
		}
		
		private void clear() {
			this.received.clear();
		}
		
		private void close() {
			this.open = false;
		}
		
		@Override
		public void send(@NonNull PresenceMessage message) {
			this.received.add(message);
		}
		
		@Override
		public boolean isOpen() {
			return this.open;
		}
	}
}
