package net.luis.sudoku.learning;

import net.luis.sudoku.db.Database;
import net.luis.sudoku.learn.LearnContent;
import net.luis.sudoku.db.schema.Schema.LearnProgressRow;
import net.luis.sudoku.repository.LearnProgressRepository;
import org.jspecify.annotations.NonNull;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The learn area's server side: a copy of what a device already holds, kept so a second device does not
 * start from an empty wiki.
 * <p>
 * Everything about the learn area works offline and is decided on the client. This service therefore stores
 * and merges, and it decides exactly one thing of its own, which is that a solve is never overwritten by
 * anything weaker.
 * <p>
 * The package is {@code learning} rather than {@code learn} on purpose: the shared core already owns
 * {@code net.luis.sudoku.learn}, and a package split across two jars resolves silently to whichever half a
 * reader is not looking at.
 */
public final class LearnService {

	private final Database database;
	private final LearnProgressRepository progress;
	private final Clock clock;

	public LearnService(@NonNull Database database, @NonNull LearnProgressRepository progress, @NonNull Clock clock) {
		this.database = database;
		this.progress = progress;
		this.clock = clock;
	}

	/**
	 * What this account has finished, across every device it has.
	 */
	public @NonNull List<LearnProgressRow> progressOf(@NonNull UUID userId) {
		return this.database.transaction(transaction -> this.progress.forUser(transaction, userId));
	}

	/**
	 * Takes on what a device reports and hands back everything the account holds afterwards.
	 * <p>
	 * Both halves happen in one transaction, so the rows the device is given back are the rows its own
	 * upload was merged into, rather than a read that another device could have moved in between.
	 *
	 * @return the merged rows, and how many of the reported ones were new information
	 */
	public @NonNull Merged sync(@NonNull UUID userId, @NonNull List<LearnProgressRow> reported) {
		return this.database.transaction(transaction -> {
			int accepted = this.progress.merge(transaction, userId, reported);
			return new Merged(this.progress.forUser(transaction, userId), accepted);
		});
	}

	/**
	 * Clears one technique's rows, which is what a client's per-technique reset syncs.
	 */
	public void reset(@NonNull UUID userId, @NonNull String technique) {
		this.database.transaction(transaction -> this.progress.reset(transaction, userId, technique));
	}

	/**
	 * Builds a row from what a device reported, stamped with the server's clock.
	 * <p>
	 * The server's clock rather than the device's, and it is deliberately not what a merge compares: a
	 * device that is wrong about the time should not be able to win an argument about which of two states
	 * is better, and the timestamp exists to answer "when did this arrive" and nothing else.
	 */
	public @NonNull LearnProgressRow rowOf(@NonNull UUID userId, @NonNull String technique, int level, int subLevel, @NonNull String state) {
		return new LearnProgressRow(userId, technique, level, subLevel, state, this.clock.instant());
	}

	/**
	 * How many techniques this account has mastered.
	 * <p>
	 * A technique is mastered when every one of its exercises is solved <em>with</em> it, which is why a
	 * partial does not count however many of them there are. The shape of the training - how many levels,
	 * how many exercises each - comes from the shared core, so the server never carries a second copy of a
	 * number the client would have to agree with.
	 */
	public static int masteredCount(@NonNull List<LearnProgressRow> rows) {
		Map<String, List<LearnProgressRow>> byTechnique = rows.stream()
			.filter(row -> LearnProgressRepository.SOLVED.equals(row.state()))
			.collect(Collectors.groupingBy(LearnProgressRow::technique));

		int mastered = 0;
		for (List<LearnProgressRow> solved : byTechnique.values()) {
			// Counted by distinct positions rather than by size: a device could report the same exercise
			// twice in one call, and nine copies of one solve is not a mastered technique.
			long distinct = solved.stream().map(row -> row.level() + ":" + row.subLevel()).distinct().count();
			if (distinct >= LearnContent.EXERCISES_PER_TECHNIQUE) {
				mastered++;
			}
		}
		return mastered;
	}

	/**
	 * The result of a sync.
	 *
	 * @param rows everything the account holds once the merge is done
	 * @param accepted how many reported rows were new information, which is what the response reports
	 */
	public record Merged(@NonNull List<LearnProgressRow> rows, int accepted) {}
}
