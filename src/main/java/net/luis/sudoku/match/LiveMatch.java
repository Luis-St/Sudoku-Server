package net.luis.sudoku.match;

import net.luis.sudoku.config.MatchConfig;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.grid.GridSize;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * The in-memory half of a running match, and the owner of its single-threaded queue
 * (server-spec 10.4).
 * <p>
 * <strong>Threading rule:</strong> every field below is touched only from {@link #submit}. Callers on
 * request or socket threads enqueue and return; they never read or mutate state directly. That is what
 * lets subclasses implement game rules as ordinary single-threaded code.
 * <p>
 * Subclasses supply the mode-specific rules: {@link RaceMatch}, {@link DuelMatch}, {@link CoopMatch}.
 */
public abstract class LiveMatch {
	
	private static final Logger log = LoggerFactory.getLogger(LiveMatch.class);
	/** One queue per match: total ordering, no locking inside the rules. */
	private final ScheduledExecutorService queue;
	/** Insertion-ordered so "first to join" is meaningful and iteration is deterministic. */
	private final Map<UUID, Connection> connections = new LinkedHashMap<>();
	private final Map<UUID, ParticipantState> participants = new LinkedHashMap<>();
	protected final Match match;
	protected final GeneratedPuzzle puzzle;
	protected final MatchConfig config;
	protected final MatchCallbacks callbacks;
	private MatchState state;
	private boolean ended;
	private @Nullable ScheduledFuture<?> graceTimer;
	
	protected LiveMatch(@NonNull Match match, @NonNull GeneratedPuzzle puzzle, @NonNull MatchConfig config,
	                    @NonNull MatchCallbacks callbacks) {
		this.match = match;
		this.puzzle = puzzle;
		this.config = config;
		this.callbacks = callbacks;
		this.state = match.state();
		this.queue = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform().name("match-" + match.id()).daemon().unstarted(runnable));
	}
	
	public @NonNull UUID id() {
		return this.match.id();
	}
	
	public @NonNull MatchMode mode() {
		return this.match.mode();
	}
	
	public @NonNull Match match() {
		return this.match;
	}
	
	protected @NonNull GridSize size() {
		return this.puzzle.puzzle().size();
	}
	
	/**
	 * Enqueues work onto this match's queue. The only legitimate way in from another thread.
	 */
	public void submit(@NonNull Runnable work) {
		try {
			this.queue.execute(() -> {
				try {
					work.run();
				} catch (RuntimeException e) {
					log.error("Match {} failed while processing an event", this.match.id(), e);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			log.debug("Match {} is shut down; dropping an event", this.match.id());
		}
	}
	
	protected @NonNull ScheduledFuture<?> schedule(@NonNull Runnable work, long delay, @NonNull TimeUnit unit) {
		return this.queue.schedule(() -> {
			try {
				work.run();
			} catch (RuntimeException e) {
				log.error("Match {} failed in a scheduled task", this.match.id(), e);
			}
		}, delay, unit);
	}
	
	protected @NonNull ScheduledFuture<?> scheduleRepeating(@NonNull Runnable work, long period, @NonNull TimeUnit unit) {
		return this.queue.scheduleAtFixedRate(() -> {
			try {
				work.run();
			} catch (RuntimeException e) {
				log.error("Match {} failed in a periodic task", this.match.id(), e);
			}
		}, period, period, unit);
	}
	
	// --- connection lifecycle, all on the queue ---
	
	/**
	 * Attaches a connection. Called on the queue.
	 */
	public void onConnect(@NonNull Connection connection) {
		UUID userId = connection.userId();
		ParticipantState participant = this.participants.computeIfAbsent(userId, id -> new ParticipantState(id, connection.displayName()));
		
		if (participant.reconnects > 0) {
			// Returning from a drop: the grace timer stops and the match resumes.
			this.cancelGrace();
		}
		this.connections.put(userId, connection);
		participant.connected = true;
		
		// MATCH_STATE on every connect and reconnect makes the protocol resynchronising by
		// construction: the client replaces its state wholesale rather than trying to patch (spec 10.3).
		connection.send(this.matchStateMessage(userId));
		this.onParticipantConnected(participant);
	}
	
	/**
	 * Detaches a connection. Called on the queue.
	 *
	 * @param explicit true for a deliberate quit, which gets no grace at all (spec 10.4)
	 */
	public void onDisconnect(@NonNull UUID userId, boolean explicit) {
		ParticipantState participant = this.participants.get(userId);
		if (participant == null || this.ended) {
			return;
		}
		
		this.connections.remove(userId);
		participant.connected = false;
		
		if (this.state != MatchState.RUNNING) {
			return;
		}
		
		if (explicit) {
			this.endMatch(null, EndReason.RESIGNED);
			return;
		}
		
		participant.reconnects++;
		if (participant.reconnects > this.config.reconnectLimit()) {
			// A flapping connection must not hold the other player hostage (spec 10.4).
			log.info("Match {}: user {} exceeded the reconnect cap", this.match.id(), userId);
			this.endMatch(null, EndReason.RECONNECT_LIMIT);
			return;
		}
		
		this.onParticipantDisconnected(participant);
		this.startGrace();
	}
	
	/**
	 * Closes one user's connection to this match without ending it - used for
	 * {@code SESSION_SUPERSEDED} and kicks.
	 */
	public void disconnectUser(@NonNull UUID userId, @NonNull String reason) {
		this.submit(() -> {
			Connection connection = this.connections.get(userId);
			if (connection != null) {
				connection.close(reason);
				this.onDisconnect(userId, false);
			}
		});
	}
	
	private void startGrace() {
		this.cancelGrace();
		int seconds = this.config.reconnectGraceSeconds();
		
		this.broadcast(MessageEnvelope.of(MessageType.MATCH_STATE, Map.of(
			"paused", true,
			"graceSeconds", seconds
		)));
		
		this.graceTimer = this.schedule(() -> {
			log.info("Match {}: reconnect grace expired", this.match.id());
			this.endMatch(null, EndReason.DISCONNECTED);
		}, seconds, TimeUnit.SECONDS);
	}
	
	private void cancelGrace() {
		if (this.graceTimer != null) {
			this.graceTimer.cancel(false);
			this.graceTimer = null;
		}
	}
	
	// --- state ---
	
	public @NonNull MatchState state() {
		return this.state;
	}
	
	protected void setState(@NonNull MatchState state) {
		this.state = state;
	}
	
	public boolean hasEnded() {
		return this.ended;
	}
	
	protected @NonNull Map<UUID, ParticipantState> participants() {
		return this.participants;
	}
	
	protected @Nullable ParticipantState participant(@NonNull UUID userId) {
		return this.participants.get(userId);
	}
	
	public @NonNull Set<UUID> connectedUserIds() {
		return Set.copyOf(this.connections.keySet());
	}
	
	protected boolean allReady() {
		return this.participants.size() == this.match.mode().capacity()
			&& this.participants.values().stream().allMatch(participant -> participant.ready);
	}
	
	/**
	 * Moves the match to RUNNING, escrowing stakes in the same transaction as the transition
	 * (spec 9a.3).
	 */
	protected void start() {
		if (this.state == MatchState.RUNNING) {
			return;
		}
		this.callbacks.onStart(this.match, List.copyOf(this.participants.keySet()));
		this.setState(MatchState.RUNNING);
		this.broadcastAll();
		this.onStarted();
	}
	
	/**
	 * Ends the match, persisting the outcome and settling stakes.
	 */
	protected void endMatch(@Nullable UUID winnerId, @NonNull EndReason reason) {
		if (this.ended) {
			return;
		}
		this.ended = true;
		this.cancelGrace();
		this.setState(reason == EndReason.COMPLETED || reason == EndReason.LIVES_EXHAUSTED
			|| reason == EndReason.STALEMATE || reason == EndReason.FORFEIT_BACKGROUNDED
			|| reason == EndReason.RESIGNED
			? MatchState.ENDED
			: MatchState.ABANDONED);
		
		Map<String, Object> payload = new java.util.HashMap<>();
		payload.put("winnerId", winnerId == null ? null : winnerId.toString());
		payload.put("reason", reason.name());
		this.broadcast(new MessageEnvelope(MessageType.MATCH_ENDED.name(), 0, System.currentTimeMillis(), payload));
		
		this.callbacks.onEnd(this.match, this.state, winnerId, reason, List.copyOf(this.participants.keySet()));
		log.info("Match {} ended: {} (winner {})", this.match.id(), reason, winnerId);
	}
	
	// --- messaging ---
	
	protected void broadcast(@NonNull MessageEnvelope message) {
		for (Connection connection : new ArrayList<>(this.connections.values())) {
			if (connection.isOpen()) {
				connection.send(message);
			}
		}
	}
	
	protected void sendTo(@NonNull UUID userId, @NonNull MessageEnvelope message) {
		Connection connection = this.connections.get(userId);
		if (connection != null && connection.isOpen()) {
			connection.send(message);
		}
	}
	
	protected void broadcastAll() {
		for (UUID userId : List.copyOf(this.connections.keySet())) {
			this.sendTo(userId, this.matchStateMessage(userId));
		}
	}
	
	void broadcastShutdown() {
		this.broadcast(MessageEnvelope.of(MessageType.MATCH_ENDED, Map.of("reason", EndReason.SERVER_RESTART.name())));
		for (Connection connection : new ArrayList<>(this.connections.values())) {
			connection.close("SERVER_SHUTDOWN");
		}
	}
	
	/**
	 * Builds the full snapshot for one participant. Per-participant because private state - a player's
	 * own pencil marks, their own bank - must not leak to the others.
	 */
	protected abstract @NonNull MessageEnvelope matchStateMessage(@NonNull UUID forUser);
	
	/**
	 * Handles one validated client message. Called on the queue.
	 */
	public abstract void onMessage(@NonNull UUID userId, @NonNull MessageType type, @NonNull Map<String, Object> payload);
	
	/** Hook: a participant attached. */
	protected void onParticipantConnected(@NonNull ParticipantState participant) {}
	
	/** Hook: a participant dropped and the grace window opened. */
	protected void onParticipantDisconnected(@NonNull ParticipantState participant) {}
	
	/** Hook: the match just moved to RUNNING. */
	protected void onStarted() {}
	
	public void shutdown() {
		this.queue.shutdownNow();
	}
	
	/**
	 * The persistence and currency side effects a live match needs, kept behind an interface so match
	 * logic stays free of JDBC and can be tested without a database.
	 */
	public interface MatchCallbacks {
		
		/** Escrow stakes and mark the match RUNNING, atomically (spec 9a.3). */
		void onStart(@NonNull Match match, @NonNull List<UUID> participants);
		
		/** Persist the outcome and settle stakes: payout to a winner, or refunds to everyone. */
		void onEnd(@NonNull Match match, @NonNull MatchState state, @Nullable UUID winnerId, @NonNull EndReason reason,
		           @NonNull List<UUID> participants);
	}
	
	/**
	 * Per-participant state that every mode needs.
	 */
	protected static final class ParticipantState {
		
		final UUID userId;
		final String displayName;
		boolean ready;
		boolean connected;
		int reconnects;
		
		ParticipantState(@NonNull UUID userId, @NonNull String displayName) {
			this.userId = userId;
			this.displayName = displayName;
		}
		
		public @NonNull UUID userId() {
			return this.userId;
		}
		
		public @NonNull String displayName() {
			return this.displayName;
		}
	}
}
