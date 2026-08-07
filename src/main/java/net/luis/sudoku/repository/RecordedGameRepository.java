package net.luis.sudoku.repository;

import net.luis.sudoku.db.schema.Schema;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code recorded_games}, the ids of the finished games already folded into
 * {@code stats} (server-spec 9).
 * <p>
 * This table exists only to make an upload idempotent, so it stores no facts about a game beyond its id:
 * what the game <em>was</em> lives in the aggregate it was added to.
 */
public final class RecordedGameRepository {

	/**
	 * Claims {@code gameId} for this user.
	 * <p>
	 * The claim and the fold it guards have to happen in one transaction, which is why this takes the
	 * caller's rather than opening its own: a claim that committed while the fold rolled back would lose
	 * the game for good, since every later retry would then be turned away as a duplicate.
	 *
	 * @return {@code true} if the game is new and should be folded, {@code false} if this is a retry of an
	 *   upload the server already accepted
	 */
	public boolean claim(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull UUID gameId, @NonNull Instant now) throws SqlException {
		if (this.isRecorded(transaction, userId, gameId)) {
			return false;
		}
		transaction.from(RECORDED_GAMES).insert(new Schema.RecordedGameRow(userId, gameId, now)).execute();
		return true;
	}

	public boolean isRecorded(@NonNull SqlTransaction transaction, @NonNull UUID userId, @NonNull UUID gameId) throws SqlException {
		return transaction.from(RECORDED_GAMES).select(RECORDED_GAME_ID)
			.where(Sql.equalTo(RECORDED_USER_ID, userId))
			.where(Sql.equalTo(RECORDED_GAME_ID, gameId))
			.fetchOneOrNull() != null;
	}

	/**
	 * Drops claims old enough that no client is still retrying them, so the table stays the size of the
	 * recent upload traffic rather than of every game ever played on this server.
	 *
	 * @return how many rows were removed
	 */
	public int deleteBefore(@NonNull SqlTransaction transaction, @NonNull Instant cutoff) throws SqlException {
		return transaction.from(RECORDED_GAMES).delete().where(Sql.lessThan(RECORDED_AT, cutoff)).execute();
	}
}
