package net.luis.sudoku.match.support;

import net.luis.sudoku.match.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory {@link Connection} that records what the server sent.
 * <p>
 * The match modes are pure single-threaded logic behind the queue, so testing them needs a recorder
 * rather than a socket.
 */
public final class FakeConnection implements Connection {
	
	private final UUID userId;
	private final String displayName;
	private final int apiVersion;
	private final List<MessageEnvelope> received = new CopyOnWriteArrayList<>();
	
	private boolean open = true;
	private @Nullable String closeReason;
	
	public FakeConnection(@NonNull UUID userId, @NonNull String displayName) {
		this(userId, displayName, net.luis.sudoku.ApiVersion.CURRENT);
	}
	
	public FakeConnection(@NonNull UUID userId, @NonNull String displayName, int apiVersion) {
		this.userId = userId;
		this.displayName = displayName;
		this.apiVersion = apiVersion;
	}
	
	public static @NonNull FakeConnection of(@NonNull String displayName) {
		return new FakeConnection(UUID.randomUUID(), displayName);
	}
	
	/** A socket attached on {@code /ws/v1/matches/{id}} or {@code /ws/v2/matches/{id}}. */
	public static @NonNull FakeConnection of(@NonNull String displayName, int apiVersion) {
		return new FakeConnection(UUID.randomUUID(), displayName, apiVersion);
	}
	
	@Override
	public int apiVersion() {
		return this.apiVersion;
	}
	
	@Override
	public @NonNull UUID userId() {
		return this.userId;
	}
	
	@Override
	public @NonNull String displayName() {
		return this.displayName;
	}
	
	@Override
	public void send(@NonNull MessageEnvelope message) {
		this.received.add(message);
	}
	
	@Override
	public void close(@NonNull String reason) {
		this.open = false;
		this.closeReason = reason;
	}
	
	@Override
	public boolean isOpen() {
		return this.open;
	}
	
	public @NonNull List<MessageEnvelope> received() {
		return Collections.unmodifiableList(this.received);
	}
	
	public @NonNull List<MessageEnvelope> receivedOf(@NonNull MessageType type) {
		return this.received.stream().filter(message -> message.type().equals(type.name())).toList();
	}
	
	public @Nullable MessageEnvelope lastOf(@NonNull MessageType type) {
		List<MessageEnvelope> all = this.receivedOf(type);
		return all.isEmpty() ? null : all.getLast();
	}
	
	public boolean sawType(@NonNull MessageType type) {
		return !this.receivedOf(type).isEmpty();
	}
	
	public @Nullable String closeReason() {
		return this.closeReason;
	}
	
	public void clear() {
		this.received.clear();
	}
}
