package net.luis.sudoku.repository;

import net.luis.sudoku.db.schema.Schema;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.Match;
import net.luis.sudoku.domain.MatchParticipant;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.match.*;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.condition.SqlCondition;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.query.row.SqlRow5;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code matches} and {@code match_participants} (server-spec 10).
 */
public final class MatchRepository {
	
	public @NonNull Match create(
		@NonNull SqlTransaction transaction, @NonNull MatchMode mode, @NonNull UUID creatorId, @NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty,
		long seed, boolean livesEnabled, boolean hintsEnabled, int stake, @NonNull String inviteToken, @NonNull Instant now
	) throws SqlException {
		Match draft = new Match(UUID.randomUUID(), mode, MatchState.WAITING, creatorId, size, variant, difficulty, seed, livesEnabled, hintsEnabled, stake, inviteToken, null, null, now, null, null);
		return transaction.from(MATCHES).insert(draft).returning().getFirst();
	}
	
	public @Nullable Match find(@NonNull SqlTransaction transaction, @NonNull UUID id) throws SqlException {
		return transaction.from(MATCHES).select().where(Sql.equalTo(MATCH_ID, id)).fetchOneOrNull();
	}
	
	public @Nullable Match findForUpdate(@NonNull SqlTransaction transaction, @NonNull UUID id) throws SqlException {
		return transaction.from(MATCHES).select().where(Sql.equalTo(MATCH_ID, id)).forUpdate().fetchOneOrNull();
	}
	
	/**
	 * Resolves a match code to the lobby it opens.
	 *
	 * Only a match that is still accepting participants can be found this way, which is what lets the code be
	 * eight characters: a code stops meaning anything the moment the match starts, so the window in which
	 * guessing one is worth anything is the length of a lobby. Matches that have started or ended keep their
	 * code on the row for the record, and are invisible here.
	 *
	 * Newest wins if two joinable rows ever share a code. {@code create} already checks the code is free
	 * before using it and there is no unique index behind that check, so two lobbies opened in the same
	 * instant could in principle collide; at 32^8 codes that is not a thing that happens, and picking one of
	 * them deterministically is better than failing the join.
	 *
	 * @param code the canonical {@code XXXX-XXXX} form, as {@code CodeGenerator.canonicalMatchCode} produces
	 * @return the joinable match holding that code, or null
	 */
	public @Nullable Match findJoinableByCode(@NonNull SqlTransaction transaction, @NonNull String code) throws SqlException {
		List<Match> found = transaction.from(MATCHES).select()
			.where(Sql.equalTo(MATCH_INVITE_TOKEN, code))
			.where(SqlCondition.anyOf(Sql.equalTo(MATCH_STATE, MatchState.CREATED), Sql.equalTo(MATCH_STATE, MatchState.WAITING)))
			.fetch();

		Match newest = null;
		for (Match match : found) {
			if (newest == null || match.createdAt().isAfter(newest.createdAt())) {
				newest = match;
			}
		}
		return newest;
	}

	/**
	 * @return matches left in a non-terminal state, which after a restart can only be wreckage
	 *   (spec 9a.3)
	 */
	public @NonNull List<Match> findUnfinished(@NonNull SqlTransaction transaction) throws SqlException {
		return transaction.from(MATCHES).select()
			.where(SqlCondition.anyOf(Sql.equalTo(MATCH_STATE, MatchState.CREATED), Sql.equalTo(MATCH_STATE, MatchState.WAITING),
				Sql.equalTo(MATCH_STATE, MatchState.RUNNING)))
			.fetch();
	}
	
	/**
	 * The match one player is currently in, if any.
	 *
	 * Only {@code RUNNING} counts. A waiting lobby is something they can walk back into from the multiplayer
	 * screen and nothing is at stake in it yet; a running match is the one that ends without them, which is
	 * the whole reason a client asks this question on startup.
	 *
	 * There is at most one - joining escrows a stake and a player can only be in one board at a time - but
	 * the id list is read rather than assumed, and the newest wins if a stale row ever survives.
	 *
	 * @return the running match this user is a participant of, or null
	 */
	public @Nullable Match findRunningFor(@NonNull SqlTransaction transaction, @NonNull UUID userId) throws SqlException {
		List<UUID> ids = transaction.from(MATCH_PARTICIPANTS).select(PARTICIPANT_MATCH_ID)
			.innerJoin(MATCHES, Sql.equalTo(MATCH_ID, PARTICIPANT_MATCH_ID))
			.where(Sql.equalTo(PARTICIPANT_USER_ID, userId))
			.where(Sql.equalTo(MATCH_STATE, MatchState.RUNNING))
			.fetch();

		Match newest = null;
		for (UUID id : ids) {
			Match match = this.find(transaction, id);
			if (match != null && (newest == null || match.createdAt().isAfter(newest.createdAt()))) {
				newest = match;
			}
		}
		return newest;
	}

	public void markRunning(@NonNull SqlTransaction transaction, @NonNull UUID id, @NonNull Instant at) throws SqlException {
		transaction.from(MATCHES).update().set(MATCH_STATE, MatchState.RUNNING).set(MATCH_STARTED_AT, at)
			.where(Sql.equalTo(MATCH_ID, id)).execute();
	}
	
	public void markEnded(@NonNull SqlTransaction transaction, @NonNull UUID id, @NonNull MatchState state, @Nullable UUID winnerId, @NonNull EndReason reason, @NonNull Instant at) throws SqlException {
		transaction.from(MATCHES).update()
			.set(MATCH_STATE, state)
			.set(MATCH_WINNER_ID, winnerId)
			.set(MATCH_END_REASON, reason)
			.set(MATCH_ENDED_AT, at)
			.where(Sql.equalTo(MATCH_ID, id))
			.execute();
	}
	
	public void addParticipant(@NonNull SqlTransaction transaction, @NonNull UUID matchId, @NonNull UUID userId, @NonNull Instant joinedAt) throws SqlException {
		Schema.ParticipantRow draft = new Schema.ParticipantRow(matchId, userId, joinedAt, null);
		transaction.from(MATCH_PARTICIPANTS).insert(draft, PARTICIPANT_MATCH_ID, PARTICIPANT_USER_ID).execute();
	}
	
	public void setResult(@NonNull SqlTransaction transaction, @NonNull UUID matchId, @NonNull UUID userId, @NonNull MatchResult result) throws SqlException {
		transaction.from(MATCH_PARTICIPANTS).update().set(PARTICIPANT_RESULT, result)
			.where(Sql.equalTo(PARTICIPANT_MATCH_ID, matchId)).where(Sql.equalTo(PARTICIPANT_USER_ID, userId)).execute();
	}
	
	public @NonNull List<MatchParticipant> participants(@NonNull SqlTransaction transaction, @NonNull UUID matchId) throws SqlException {
		List<SqlRow5<UUID, UUID, String, Instant, MatchResult>> rows = transaction.from(MATCH_PARTICIPANTS)
			.select(PARTICIPANT_MATCH_ID, PARTICIPANT_USER_ID, USER_DISPLAY_NAME, PARTICIPANT_JOINED_AT, PARTICIPANT_RESULT)
			.innerJoin(USERS, Sql.equalTo(USER_ID, PARTICIPANT_USER_ID))
			.where(Sql.equalTo(PARTICIPANT_MATCH_ID, matchId))
			.orderBy(PARTICIPANT_JOINED_AT.ascending())
			.fetch();
		
		List<MatchParticipant> participants = new ArrayList<>(rows.size());
		for (SqlRow5<UUID, UUID, String, Instant, MatchResult> row : rows) {
			participants.add(new MatchParticipant(row.first(), row.second(), row.third(), row.fourth(), row.fifth()));
		}
		return participants;
	}
	
	public int countParticipants(@NonNull SqlTransaction transaction, @NonNull UUID matchId) throws SqlException {
		return transaction.from(MATCH_PARTICIPANTS).select(Sql.count(PARTICIPANT_USER_ID, false))
			.where(Sql.equalTo(PARTICIPANT_MATCH_ID, matchId)).fetchOne().intValue();
	}
}
