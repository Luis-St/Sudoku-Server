package net.luis.sudoku.repository;

import net.luis.sudoku.db.schema.Schema.MatchRequestRow;
import net.luis.sudoku.match.MatchMode;
import net.luis.sudoku.match.MatchState;
import net.luis.sudoku.presence.PendingMatchRequest;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.condition.SqlCondition;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.query.row.SqlRow7;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * Reads and writes {@code match_requests}, the stored form of "come play with me" (feature-spec 9.7).
 */
public final class MatchRequestRepository {

	/**
	 * The states a request is still worth delivering in. A match that is already running has no room for
	 * the person being asked, and a terminal one has nothing to join at all - in either case the row is
	 * there but the request is dead, which a reader must not hand to a client.
	 */
	private static @NonNull SqlCondition joinable() {
		return SqlCondition.anyOf(Sql.equalTo(MATCH_STATE, MatchState.CREATED), Sql.equalTo(MATCH_STATE, MatchState.WAITING));
	}

	public @NonNull UUID create(
		@NonNull SqlTransaction transaction, @NonNull UUID targetUserId, @NonNull UUID matchId, @NonNull UUID fromUserId, @NonNull Instant expiresAt, @NonNull Instant now
	) throws SqlException {
		MatchRequestRow draft = new MatchRequestRow(UUID.randomUUID(), targetUserId, matchId, fromUserId, expiresAt, now);
		transaction.from(MATCH_REQUESTS).insert(draft).execute();
		return draft.id();
	}

	/**
	 * Drops any earlier request for the same match and target, so asking twice refreshes the invitation
	 * rather than stacking up a second copy of it - and so a re-ask after the first one expired works.
	 */
	public void deleteFor(@NonNull SqlTransaction transaction, @NonNull UUID targetUserId, @NonNull UUID matchId) throws SqlException {
		transaction.from(MATCH_REQUESTS).delete()
			.where(Sql.equalTo(REQUEST_TARGET_USER_ID, targetUserId))
			.where(Sql.equalTo(REQUEST_MATCH_ID, matchId))
			.execute();
	}

	/**
	 * Everything still worth showing this player, newest last, with the match's own mode, stake and invite
	 * token read live rather than copied at request time.
	 */
	public @NonNull List<PendingMatchRequest> findPending(@NonNull SqlTransaction transaction, @NonNull UUID targetUserId, @NonNull Instant now) throws SqlException {
		List<SqlRow7<UUID, UUID, String, MatchMode, Integer, UUID, String>> rows = transaction.from(MATCH_REQUESTS)
			.select(REQUEST_ID, REQUEST_MATCH_ID, MATCH_INVITE_TOKEN, MATCH_MODE, MATCH_STAKE, REQUEST_FROM_USER_ID, USER_DISPLAY_NAME)
			.innerJoin(MATCHES, Sql.equalTo(MATCH_ID, REQUEST_MATCH_ID))
			.innerJoin(USERS, Sql.equalTo(USER_ID, REQUEST_FROM_USER_ID))
			.where(Sql.equalTo(REQUEST_TARGET_USER_ID, targetUserId))
			.where(Sql.greaterThan(REQUEST_EXPIRES_AT, now))
			.where(joinable())
			.orderBy(REQUEST_CREATED_AT.ascending())
			.fetch();

		List<PendingMatchRequest> requests = new ArrayList<>(rows.size());
		for (SqlRow7<UUID, UUID, String, MatchMode, Integer, UUID, String> row : rows) {
			requests.add(new PendingMatchRequest(row.first(), row.second(), row.third(), row.fourth(), row.fifth(), row.sixth(), row.seventh()));
		}
		return requests;
	}

	public @Nullable MatchRequestRow find(@NonNull SqlTransaction transaction, @NonNull UUID id) throws SqlException {
		return transaction.from(MATCH_REQUESTS).select().where(Sql.equalTo(REQUEST_ID, id)).fetchOneOrNull();
	}

	public void delete(@NonNull SqlTransaction transaction, @NonNull UUID id) throws SqlException {
		transaction.from(MATCH_REQUESTS).delete().where(Sql.equalTo(REQUEST_ID, id)).execute();
	}

	public int deleteExpired(@NonNull SqlTransaction transaction, @NonNull Instant now) throws SqlException {
		return transaction.from(MATCH_REQUESTS).delete().where(Sql.lessThanOrEqualTo(REQUEST_EXPIRES_AT, now)).execute();
	}
}
