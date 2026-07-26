package net.luis.sudoku.match.support;

import net.luis.sudoku.domain.Match;
import net.luis.sudoku.match.EndReason;
import net.luis.sudoku.match.LiveMatch;
import net.luis.sudoku.match.MatchState;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records the persistence side effects a live match asks for, so mode logic can be tested without a
 * database.
 */
public final class RecordingCallbacks implements LiveMatch.MatchCallbacks {

	private final AtomicInteger starts = new AtomicInteger();

	private volatile @Nullable Ended ended;

	@Override
	public void onStart(@NonNull Match match, @NonNull List<UUID> participants) {
		this.starts.incrementAndGet();
	}

	@Override
	public void onEnd(@NonNull Match match, @NonNull MatchState state, @Nullable UUID winnerId,
					  @NonNull EndReason reason, @NonNull List<UUID> participants) {
		this.ended = new Ended(state, winnerId, reason);
	}

	public int starts() {
		return this.starts.get();
	}

	public @Nullable Ended ended() {
		return this.ended;
	}

	/**
	 * @param state the terminal state persisted
	 * @param winnerId the winner, or null for a draw or abandonment
	 * @param reason why it ended
	 */
	public record Ended(@NonNull MatchState state, @Nullable UUID winnerId, @NonNull EndReason reason) {}
}
