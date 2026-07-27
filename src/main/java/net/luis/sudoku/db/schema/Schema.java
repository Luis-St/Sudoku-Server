package net.luis.sudoku.db.schema;

import net.luis.sudoku.currency.LedgerReason;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.match.*;
import net.luis.sudoku.permission.Role;
import net.luis.utils.io.database.table.*;
import net.luis.utils.io.database.type.SqlType;
import net.luis.utils.io.database.type.SqlTypes;
import net.luis.utils.io.database.type.parameter.SqlParameter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The database schema as LUtils {@link SqlTable} and {@link SqlColumn} definitions (server-spec 5).
 * <p>
 * This is the single source of truth for table and column names. Migrations build the physical schema
 * from these definitions, and repositories query through them via the LUtils query builder, so a rename
 * cannot leave the two out of step - which is exactly what hand-written SQL strings on both sides used
 * to allow.
 * <p>
 * Type conventions follow the spec's mapping table: {@code UUID} ids, {@code INSTANT} timestamps
 * (TIMESTAMPTZ), {@code BYTES} public keys (BYTEA), {@code BOOLEAN} flags, {@code LOCAL_DATE} daily
 * dates computed in the server zone at write time, and {@code TEXT} for enum-ish columns.
 * <p>
 * Every enum and value-object column is {@link SqlType#map} onto its stored representation, so the
 * automatic row mapper (which invokes the entity's constructor with the column values in order) sees the
 * real domain type rather than the raw string or int and can find a matching constructor. A table whose
 * row shape has no independent meaning outside persistence uses a small nested record here rather than a
 * top-level domain type.
 */
public final class Schema {
	
	private static final SqlType<Role> ROLE_TYPE =
		SqlTypes.STRING.configure(SqlParameter.length(16)).map(Role.class, Role::name, Role::valueOf);
	private static final SqlType<KeyAlgorithm> KEY_ALGORITHM_TYPE =
		SqlTypes.STRING.configure(SqlParameter.length(16)).map(KeyAlgorithm.class, KeyAlgorithm::name, KeyAlgorithm::valueOf);
	private static final SqlType<MatchMode> MATCH_MODE_TYPE =
		SqlTypes.STRING.configure(SqlParameter.length(16)).map(MatchMode.class, MatchMode::name, MatchMode::valueOf);
	private static final SqlType<MatchState> MATCH_STATE_TYPE =
		SqlTypes.STRING.configure(SqlParameter.length(16)).map(MatchState.class, MatchState::name, MatchState::valueOf);
	private static final SqlType<Variant> VARIANT_TYPE =
		SqlTypes.STRING.configure(SqlParameter.length(16)).map(Variant.class, Variant::name, Variant::valueOf);
	private static final SqlType<GridSize> GRID_SIZE_TYPE =
		SqlTypes.INTEGER.map(GridSize.class, GridSize::n, GridSize::ofEdgeLength);
	private static final SqlType<Difficulty> DIFFICULTY_TYPE =
		SqlTypes.INTEGER.map(Difficulty.class, Difficulty::index, Difficulty::ofIndex);
	private static final SqlType<DailyOutcome> DAILY_OUTCOME_TYPE =
		SqlTypes.STRING.configure(SqlParameter.length(16)).map(DailyOutcome.class, DailyOutcome::name, DailyOutcome::valueOf);
	private static final SqlType<LedgerReason> LEDGER_REASON_TYPE =
		SqlTypes.STRING.configure(SqlParameter.length(16)).map(LedgerReason.class, LedgerReason::name, LedgerReason::valueOf);
	/** Nullable: a match still running has no {@code end_reason}. */
	private static final SqlType<EndReason> END_REASON_TYPE =
		SqlTypes.STRING.configure(SqlParameter.length(32))
			.map(EndReason.class, reason -> reason == null ? null : reason.name(), name -> name == null ? null : EndReason.valueOf(name));
	/** Nullable: a participant has no {@code result} while the match is running. */
	private static final SqlType<MatchResult> MATCH_RESULT_TYPE =
		SqlTypes.STRING.configure(SqlParameter.length(16))
			.map(MatchResult.class, result -> result == null ? null : result.name(), name -> name == null ? null : MatchResult.valueOf(name));
	/**
	 * Entity ids as the native Postgres {@code uuid} type, matching the {@code UUID PRIMARY KEY} columns
	 * the plain-SQL migrations create (DDL stays outside LUtils - see class javadoc and
	 * {@code db/Migrations}). Must be the literal {@link SqlTypes#UUID} singleton, not a lookalike built
	 * via {@code .map()}: the Postgres dialect's {@code Types.OTHER} bind/read override
	 * ({@code setObject(..., Types.OTHER)} / {@code getObject(..., UUID.class)}) is registered against
	 * that exact instance, not against its structure - an equivalent type falls back to generic
	 * {@code FIXED_STRING} binding and Postgres then rejects it ("column is of type uuid but expression
	 * is of type character"). An earlier revision built a CHAR(36) lookalike to dodge a *migration
	 * introspection* bug in LUtils 10.4.0-beta.2 (fixed in beta.3); that workaround only ever mattered
	 * for {@code SqlMigrationRunner}, which this schema never uses, and it broke query execution instead.
	 */
	public static final SqlType<UUID> UUID_TYPE = SqlTypes.UUID;
	/**
	 * TIMESTAMPTZ with microsecond precision, matching what Postgres stores natively.
	 * <p>
	 * {@link SqlTypes#INSTANT} is parameterizable, so it has to be configured before use; six digits is
	 * Postgres' own resolution, and asking for more would silently round-trip differently.
	 */
	public static final SqlType<Instant> TIMESTAMP = SqlTypes.INSTANT.configure(SqlParameter.fractional(6));
	
	// --- server_meta ---------------------------------------------------------------------------
	
	/**
	 * Both columns are plain {@link SqlTypes#TEXT}, matching the raw {@code CREATE TABLE} the migration
	 * bootstrap issues (see {@code db/Migrations}) exactly - {@code key}/{@code value} are unbounded
	 * {@code TEXT}, not {@code VARCHAR(n)}, in the real DDL, and this table is the one place a
	 * LUtils-driven {@code createIfNotExists()} genuinely runs against a schema that raw SQL also
	 * creates, so the two must agree byte-for-byte or a fresh bootstrap could produce a narrower column
	 * than an already-migrated deployment has.
	 */
	public static final SqlTable<ServerMetaRow> SERVER_META = SqlTable.create(ServerMetaRow.class, "server_meta");
	public static final SqlColumn<ServerMetaRow, String> META_KEY = SERVER_META.column("key", SqlTypes.TEXT, ServerMetaRow::key, col -> col.primaryKey().notNull());
	public static final SqlColumn<ServerMetaRow, String> META_VALUE = SERVER_META.column("value", SqlTypes.TEXT, ServerMetaRow::value, SqlColumnBuilder::notNull);
	public static final SqlTable<User> USERS = SqlTable.create(User.class, "users");
	
	// --- users ---------------------------------------------------------------------------------
	public static final SqlColumn<User, UUID> USER_ID = USERS.column("id", UUID_TYPE, User::id, col -> col.primaryKey().notNull());
	public static final SqlColumn<User, String> USER_DISPLAY_NAME = USERS.column("display_name", SqlTypes.STRING.configure(SqlParameter.length(32)), User::displayName, col -> col.notNull().unique());
	public static final SqlColumn<User, Role> USER_ROLE = USERS.column("role", ROLE_TYPE, User::role, SqlColumnBuilder::notNull);
	public static final SqlColumn<User, Instant> USER_CREATED_AT = USERS.column("created_at", TIMESTAMP, User::createdAt, SqlColumnBuilder::notNull);
	/** Kicked users are revoked, never deleted: their historical results are retained (spec 7.2). */
	public static final SqlColumn<User, Boolean> USER_REVOKED = USERS.column("revoked", SqlTypes.BOOLEAN, User::revoked, col -> col.notNull().defaultValue(false));
	public static final SqlTable<Device> DEVICES = SqlTable.create(Device.class, "devices");
	
	// --- devices -------------------------------------------------------------------------------
	public static final SqlColumn<Device, UUID> DEVICE_ID = DEVICES.column("id", UUID_TYPE, Device::id, col -> col.primaryKey().notNull());
	public static final SqlColumn<Device, UUID> DEVICE_USER_ID = DEVICES.column("user_id", UUID_TYPE, Device::userId, SqlColumnBuilder::notNull);
	public static final SqlColumn<Device, byte[]> DEVICE_PUBLIC_KEY = DEVICES.column("public_key", SqlTypes.LARGE_BYTES, Device::publicKey, col -> col.notNull().unique());
	/** Devices need not agree: Android uses ECDSA P-256, other clients may use Ed25519 (spec 5). */
	public static final SqlColumn<Device, KeyAlgorithm> DEVICE_KEY_ALGORITHM = DEVICES.column("key_algorithm", KEY_ALGORITHM_TYPE, Device::keyAlgorithm, SqlColumnBuilder::notNull);
	public static final SqlColumn<Device, String> DEVICE_LABEL = DEVICES.column("label", SqlTypes.STRING.configure(SqlParameter.length(64)), Device::label, SqlColumnBuilder::notNull);
	public static final SqlColumn<Device, Instant> DEVICE_CREATED_AT = DEVICES.column("created_at", TIMESTAMP, Device::createdAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<Device, Instant> DEVICE_LAST_SEEN_AT = DEVICES.column("last_seen_at", TIMESTAMP, Device::lastSeenAt);
	public static final SqlColumn<Device, Boolean> DEVICE_REVOKED = DEVICES.column("revoked", SqlTypes.BOOLEAN, Device::revoked, col -> col.notNull().defaultValue(false));
	public static final SqlTable<Invite> INVITES = SqlTable.create(Invite.class, "invites");
	
	// --- invites -------------------------------------------------------------------------------
	public static final SqlColumn<Invite, String> INVITE_CODE = INVITES.column("code", SqlTypes.STRING.configure(SqlParameter.length(32)), Invite::code, col -> col.primaryKey().notNull());
	/** Null for the bootstrap invite, which predates every user. */
	public static final SqlColumn<Invite, UUID> INVITE_CREATED_BY = INVITES.column("created_by", UUID_TYPE, Invite::createdBy);
	public static final SqlColumn<Invite, Role> INVITE_GRANTS_ROLE = INVITES.column("grants_role", ROLE_TYPE, Invite::grantsRole, SqlColumnBuilder::notNull);
	public static final SqlColumn<Invite, Instant> INVITE_EXPIRES_AT = INVITES.column("expires_at", TIMESTAMP, Invite::expiresAt);
	public static final SqlColumn<Invite, UUID> INVITE_CONSUMED_BY_DEVICE = INVITES.column("consumed_by_device", UUID_TYPE, Invite::consumedByDevice);
	public static final SqlColumn<Invite, Instant> INVITE_CONSUMED_AT = INVITES.column("consumed_at", TIMESTAMP, Invite::consumedAt);
	public static final SqlColumn<Invite, Boolean> INVITE_REVOKED = INVITES.column("revoked", SqlTypes.BOOLEAN, Invite::revoked, col -> col.notNull().defaultValue(false));
	public static final SqlColumn<Invite, Instant> INVITE_CREATED_AT = INVITES.column("created_at", TIMESTAMP, Invite::createdAt, SqlColumnBuilder::notNull);
	public static final SqlTable<LinkCodeRow> LINK_CODES = SqlTable.create(LinkCodeRow.class, "link_codes");
	
	// --- link_codes ----------------------------------------------------------------------------
	public static final SqlColumn<LinkCodeRow, String> LINK_CODE = LINK_CODES.column("code", SqlTypes.STRING.configure(SqlParameter.length(32)), LinkCodeRow::code, col -> col.primaryKey().notNull());
	public static final SqlColumn<LinkCodeRow, UUID> LINK_USER_ID = LINK_CODES.column("user_id", UUID_TYPE, LinkCodeRow::userId, SqlColumnBuilder::notNull);
	/** Minutes, not hours: the code lives only as long as it takes to walk to the other device. */
	public static final SqlColumn<LinkCodeRow, Instant> LINK_EXPIRES_AT = LINK_CODES.column("expires_at", TIMESTAMP, LinkCodeRow::expiresAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<LinkCodeRow, Instant> LINK_CONSUMED_AT = LINK_CODES.column("consumed_at", TIMESTAMP, LinkCodeRow::consumedAt);
	public static final SqlColumn<LinkCodeRow, Instant> LINK_CREATED_AT = LINK_CODES.column("created_at", TIMESTAMP, LinkCodeRow::createdAt, SqlColumnBuilder::notNull);
	public static final SqlTable<Session> SESSIONS = SqlTable.create(Session.class, "sessions");
	// --- sessions ------------------------------------------------------------------------------
	public static final SqlColumn<Session, String> SESSION_TOKEN = SESSIONS.column("token", SqlTypes.STRING.configure(SqlParameter.length(64)), Session::token, col -> col.primaryKey().notNull());
	/** UNIQUE enforces the single-active-session rule (spec 6.2) in the schema, not just in code. */
	public static final SqlColumn<Session, UUID> SESSION_USER_ID = SESSIONS.column("user_id", UUID_TYPE, Session::userId, col -> col.notNull().unique());
	public static final SqlColumn<Session, UUID> SESSION_DEVICE_ID = SESSIONS.column("device_id", UUID_TYPE, Session::deviceId, SqlColumnBuilder::notNull);
	public static final SqlColumn<Session, Instant> SESSION_ISSUED_AT = SESSIONS.column("issued_at", TIMESTAMP, Session::issuedAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<Session, Instant> SESSION_EXPIRES_AT = SESSIONS.column("expires_at", TIMESTAMP, Session::expiresAt, SqlColumnBuilder::notNull);
	public static final SqlTable<AuthChallengeRow> AUTH_CHALLENGES = SqlTable.create(AuthChallengeRow.class, "auth_challenges");
	// --- auth_challenges -----------------------------------------------------------------------
	public static final SqlColumn<AuthChallengeRow, byte[]> CHALLENGE_NONCE = AUTH_CHALLENGES.column("nonce", SqlTypes.LARGE_BYTES, AuthChallengeRow::nonce, col -> col.primaryKey().notNull());
	public static final SqlColumn<AuthChallengeRow, byte[]> CHALLENGE_PUBLIC_KEY = AUTH_CHALLENGES.column("public_key", SqlTypes.LARGE_BYTES, AuthChallengeRow::publicKey, SqlColumnBuilder::notNull);
	public static final SqlColumn<AuthChallengeRow, Instant> CHALLENGE_EXPIRES_AT = AUTH_CHALLENGES.column("expires_at", TIMESTAMP, AuthChallengeRow::expiresAt, SqlColumnBuilder::notNull);
	public static final SqlTable<DailyResult> DAILY_RESULTS = SqlTable.create(DailyResult.class, "daily_results");
	public static final SqlColumn<DailyResult, Long> RESULT_ID = DAILY_RESULTS.column("id", SqlTypes.LONG, DailyResult::id, col -> col.primaryKey().notNull().autoIncrement());
	// --- daily_results -------------------------------------------------------------------------
	public static final SqlColumn<DailyResult, UUID> RESULT_USER_ID = DAILY_RESULTS.column("user_id", UUID_TYPE, DailyResult::userId, SqlColumnBuilder::notNull);
	public static final SqlColumn<DailyResult, LocalDate> RESULT_DATE = DAILY_RESULTS.column("date", SqlTypes.LOCAL_DATE, DailyResult::date, SqlColumnBuilder::notNull);
	public static final SqlColumn<DailyResult, Integer> RESULT_DIFFICULTY = DAILY_RESULTS.column("difficulty", SqlTypes.INTEGER, DailyResult::difficulty, SqlColumnBuilder::notNull);
	/** Restarts are unlimited after a failure; SOLVED locks the date (spec 8.2). */
	public static final SqlColumn<DailyResult, Integer> RESULT_ATTEMPT_NO = DAILY_RESULTS.column("attempt_no", SqlTypes.INTEGER, DailyResult::attemptNo, SqlColumnBuilder::notNull);
	public static final SqlColumn<DailyResult, DailyOutcome> RESULT_OUTCOME = DAILY_RESULTS.column("outcome", DAILY_OUTCOME_TYPE, DailyResult::outcome, SqlColumnBuilder::notNull);
	public static final SqlColumn<DailyResult, Long> RESULT_ELAPSED_MS = DAILY_RESULTS.column("elapsed_ms", SqlTypes.LONG, DailyResult::elapsedMs, SqlColumnBuilder::notNull);
	public static final SqlColumn<DailyResult, Integer> RESULT_MISTAKES = DAILY_RESULTS.column("mistakes", SqlTypes.INTEGER, DailyResult::mistakes, col -> col.notNull().defaultValue(0));
	public static final SqlColumn<DailyResult, Integer> RESULT_HINTS_USED = DAILY_RESULTS.column("hints_used", SqlTypes.INTEGER, DailyResult::hintsUsed, col -> col.notNull().defaultValue(0));
	/** Whether the solve-order replay check passed. */
	public static final SqlColumn<DailyResult, Boolean> RESULT_VERIFIED = DAILY_RESULTS.column("verified", SqlTypes.BOOLEAN, DailyResult::verified, SqlColumnBuilder::notNull);
	public static final SqlColumn<DailyResult, Instant> RESULT_CREATED_AT = DAILY_RESULTS.column("created_at", TIMESTAMP, DailyResult::createdAt, SqlColumnBuilder::notNull);
	public static final SqlTable<Streak> STREAKS = SqlTable.create(Streak.class, "streaks");
	public static final SqlColumn<Streak, UUID> STREAK_USER_ID = STREAKS.column("user_id", UUID_TYPE, Streak::userId, col -> col.primaryKey().notNull());
	/**
	 * Named {@code *_streak} rather than the spec's bare {@code current}/{@code longest}: {@code current}
	 * is a SQL keyword and quoting it at every call site is worse than two extra words.
	 */
	public static final SqlColumn<Streak, Integer> STREAK_CURRENT = STREAKS.column("current_streak", SqlTypes.INTEGER, Streak::current, col -> col.notNull().defaultValue(0));
	
	// --- streaks -------------------------------------------------------------------------------
	public static final SqlColumn<Streak, Integer> STREAK_LONGEST = STREAKS.column("longest_streak", SqlTypes.INTEGER, Streak::longest, col -> col.notNull().defaultValue(0));
	public static final SqlColumn<Streak, LocalDate> STREAK_LAST_COMPLETED = STREAKS.column("last_completed_date", SqlTypes.LOCAL_DATE, Streak::lastCompletedDate);
	public static final SqlTable<StatsEntry> STATS = SqlTable.create(StatsEntry.class, "stats");
	public static final SqlColumn<StatsEntry, UUID> STATS_USER_ID = STATS.column("user_id", UUID_TYPE, StatsEntry::userId, SqlColumnBuilder::notNull);
	// --- stats ---------------------------------------------------------------------------------
	public static final SqlColumn<StatsEntry, Integer> STATS_SIZE = STATS.column("size", SqlTypes.INTEGER, StatsEntry::size, SqlColumnBuilder::notNull);
	public static final SqlColumn<StatsEntry, String> STATS_VARIANT = STATS.column("variant", SqlTypes.STRING.configure(SqlParameter.length(16)), StatsEntry::variant, SqlColumnBuilder::notNull);
	/** 6 = LISA, single-player only, but it does appear in a player's own statistics. */
	public static final SqlColumn<StatsEntry, Integer> STATS_DIFFICULTY = STATS.column("difficulty", SqlTypes.INTEGER, StatsEntry::difficulty, SqlColumnBuilder::notNull);
	public static final SqlColumn<StatsEntry, Integer> STATS_GAMES_PLAYED = STATS.column("games_played", SqlTypes.INTEGER, StatsEntry::gamesPlayed, col -> col.notNull().defaultValue(0));
	public static final SqlColumn<StatsEntry, Integer> STATS_SOLVED = STATS.column("solved", SqlTypes.INTEGER, StatsEntry::solved, col -> col.notNull().defaultValue(0));
	public static final SqlColumn<StatsEntry, Integer> STATS_FAILED = STATS.column("failed", SqlTypes.INTEGER, StatsEntry::failed, col -> col.notNull().defaultValue(0));
	public static final SqlColumn<StatsEntry, Long> STATS_BEST_TIME_MS = STATS.column("best_time_ms", SqlTypes.LONG, StatsEntry::bestTimeMs);
	/** The running total, not the mean: an average cannot be merged incrementally without it. */
	public static final SqlColumn<StatsEntry, Long> STATS_TOTAL_TIME_MS = STATS.column("total_time_ms", SqlTypes.LONG, StatsEntry::totalTimeMs, col -> col.notNull().defaultValue(0L));
	public static final SqlColumn<StatsEntry, Integer> STATS_HINTS_USED = STATS.column("hints_used", SqlTypes.INTEGER, StatsEntry::hintsUsed, col -> col.notNull().defaultValue(0));
	public static final SqlTable<LeaderboardRow> DAILY_LEADERBOARD = SqlTable.create(LeaderboardRow.class, "daily_leaderboard");
	public static final SqlColumn<LeaderboardRow, LocalDate> LEADERBOARD_DATE = DAILY_LEADERBOARD.column("date", SqlTypes.LOCAL_DATE, LeaderboardRow::date, SqlColumnBuilder::notNull);
	// --- daily_leaderboard ---------------------------------------------------------------------
	public static final SqlColumn<LeaderboardRow, Integer> LEADERBOARD_DIFFICULTY = DAILY_LEADERBOARD.column("difficulty", SqlTypes.INTEGER, LeaderboardRow::difficulty, SqlColumnBuilder::notNull);
	public static final SqlColumn<LeaderboardRow, UUID> LEADERBOARD_USER_ID = DAILY_LEADERBOARD.column("user_id", UUID_TYPE, LeaderboardRow::userId, SqlColumnBuilder::notNull);
	public static final SqlColumn<LeaderboardRow, Long> LEADERBOARD_ELAPSED_MS = DAILY_LEADERBOARD.column("elapsed_ms", SqlTypes.LONG, LeaderboardRow::elapsedMs, SqlColumnBuilder::notNull);
	public static final SqlColumn<LeaderboardRow, Integer> LEADERBOARD_ATTEMPTS = DAILY_LEADERBOARD.column("attempts", SqlTypes.INTEGER, LeaderboardRow::attempts, col -> col.notNull().defaultValue(1));
	/** Recorded, but never exposed in the ranking response (spec 8.6). */
	public static final SqlColumn<LeaderboardRow, Integer> LEADERBOARD_HINTS_USED = DAILY_LEADERBOARD.column("hints_used", SqlTypes.INTEGER, LeaderboardRow::hintsUsed, col -> col.notNull().defaultValue(0));
	public static final SqlTable<Match> MATCHES = SqlTable.create(Match.class, "matches");
	public static final SqlColumn<Match, UUID> MATCH_ID = MATCHES.column("id", UUID_TYPE, Match::id, col -> col.primaryKey().notNull());
	public static final SqlColumn<Match, MatchMode> MATCH_MODE = MATCHES.column("mode", MATCH_MODE_TYPE, Match::mode, SqlColumnBuilder::notNull);
	// --- matches -------------------------------------------------------------------------------
	public static final SqlColumn<Match, MatchState> MATCH_STATE = MATCHES.column("state", MATCH_STATE_TYPE, Match::state, SqlColumnBuilder::notNull);
	public static final SqlColumn<Match, UUID> MATCH_CREATOR_ID = MATCHES.column("creator_id", UUID_TYPE, Match::creatorId, SqlColumnBuilder::notNull);
	public static final SqlColumn<Match, GridSize> MATCH_SIZE = MATCHES.column("size", GRID_SIZE_TYPE, Match::size, SqlColumnBuilder::notNull);
	public static final SqlColumn<Match, Variant> MATCH_VARIANT = MATCHES.column("variant", VARIANT_TYPE, Match::variant, SqlColumnBuilder::notNull);
	/** LISA (6) is rejected for every multiplayer mode (spec 10.1). */
	public static final SqlColumn<Match, Difficulty> MATCH_DIFFICULTY = MATCHES.column("difficulty", DIFFICULTY_TYPE, Match::difficulty, SqlColumnBuilder::notNull);
	/** The PuzzleKey seed; the grid itself is never stored or sent (spec 1). */
	public static final SqlColumn<Match, Long> MATCH_SEED = MATCHES.column("seed", SqlTypes.LONG, Match::seed, SqlColumnBuilder::notNull);
	public static final SqlColumn<Match, Boolean> MATCH_LIVES_ENABLED = MATCHES.column("lives_enabled", SqlTypes.BOOLEAN, Match::livesEnabled, col -> col.notNull().defaultValue(false));
	/** No minimum, no maximum (spec 9a.3). 0 means anyone may join. */
	public static final SqlColumn<Match, Integer> MATCH_STAKE = MATCHES.column("stake", SqlTypes.INTEGER, Match::stake, col -> col.notNull().defaultValue(0));
	public static final SqlColumn<Match, String> MATCH_INVITE_TOKEN = MATCHES.column("invite_token", SqlTypes.STRING.configure(SqlParameter.length(64)), Match::inviteToken, SqlColumnBuilder::notNull);
	public static final SqlColumn<Match, UUID> MATCH_WINNER_ID = MATCHES.column("winner_id", UUID_TYPE, Match::winnerId);
	public static final SqlColumn<Match, EndReason> MATCH_END_REASON = MATCHES.column("end_reason", END_REASON_TYPE, Match::endReason);
	public static final SqlColumn<Match, Instant> MATCH_CREATED_AT = MATCHES.column("created_at", TIMESTAMP, Match::createdAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<Match, Instant> MATCH_STARTED_AT = MATCHES.column("started_at", TIMESTAMP, Match::startedAt);
	public static final SqlColumn<Match, Instant> MATCH_ENDED_AT = MATCHES.column("ended_at", TIMESTAMP, Match::endedAt);
	public static final SqlTable<ParticipantRow> MATCH_PARTICIPANTS = SqlTable.create(ParticipantRow.class, "match_participants");
	public static final SqlColumn<ParticipantRow, UUID> PARTICIPANT_MATCH_ID = MATCH_PARTICIPANTS.column("match_id", UUID_TYPE, ParticipantRow::matchId, SqlColumnBuilder::notNull);
	public static final SqlColumn<ParticipantRow, UUID> PARTICIPANT_USER_ID = MATCH_PARTICIPANTS.column("user_id", UUID_TYPE, ParticipantRow::userId, SqlColumnBuilder::notNull);
	// --- match_participants --------------------------------------------------------------------
	public static final SqlColumn<ParticipantRow, Instant> PARTICIPANT_JOINED_AT = MATCH_PARTICIPANTS.column("joined_at", TIMESTAMP, ParticipantRow::joinedAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<ParticipantRow, MatchResult> PARTICIPANT_RESULT = MATCH_PARTICIPANTS.column("result", MATCH_RESULT_TYPE, ParticipantRow::result);
	public static final SqlTable<LedgerRow> CURRENCY_LEDGER = SqlTable.create(LedgerRow.class, "currency_ledger");
	public static final SqlColumn<LedgerRow, Long> LEDGER_ID = CURRENCY_LEDGER.column("id", SqlTypes.LONG, LedgerRow::id, col -> col.primaryKey().notNull().autoIncrement());
	public static final SqlColumn<LedgerRow, UUID> LEDGER_USER_ID = CURRENCY_LEDGER.column("user_id", UUID_TYPE, LedgerRow::userId, SqlColumnBuilder::notNull);
	public static final SqlColumn<LedgerRow, Integer> LEDGER_DELTA = CURRENCY_LEDGER.column("delta", SqlTypes.INTEGER, LedgerRow::delta, SqlColumnBuilder::notNull);
	// --- currency_ledger -----------------------------------------------------------------------
	public static final SqlColumn<LedgerRow, LedgerReason> LEDGER_REASON = CURRENCY_LEDGER.column("reason", LEDGER_REASON_TYPE, LedgerRow::reason, SqlColumnBuilder::notNull);
	public static final SqlColumn<LedgerRow, UUID> LEDGER_MATCH_ID = CURRENCY_LEDGER.column("match_id", UUID_TYPE, LedgerRow::matchId);
	public static final SqlColumn<LedgerRow, Instant> LEDGER_CREATED_AT = CURRENCY_LEDGER.column("created_at", TIMESTAMP, LedgerRow::createdAt, SqlColumnBuilder::notNull);
	public static final SqlTable<PreferenceRow> DAILY_PREFERENCES = SqlTable.create(PreferenceRow.class, "daily_preferences");
	public static final SqlColumn<PreferenceRow, UUID> PREFERENCE_USER_ID = DAILY_PREFERENCES.column("user_id", UUID_TYPE, PreferenceRow::userId, col -> col.primaryKey().notNull());
	public static final SqlColumn<PreferenceRow, Integer> PREFERENCE_DIFFICULTY = DAILY_PREFERENCES.column("daily_difficulty", SqlTypes.INTEGER, PreferenceRow::difficulty, col -> col.notNull().defaultValue(3));
	public static final SqlColumn<PreferenceRow, Instant> PREFERENCE_UPDATED_AT = DAILY_PREFERENCES.column("updated_at", TIMESTAMP, PreferenceRow::updatedAt, SqlColumnBuilder::notNull);
	/**
	 * The difficulty locked in for one player on one date.
	 * <p>
	 * Written on the first {@code /daily} request of a date and never updated, which is the entire
	 * mechanism behind "a preference change takes effect from the next day only".
	 */
	public static final SqlTable<AssignmentRow> DAILY_ASSIGNMENTS = SqlTable.create(AssignmentRow.class, "daily_assignments");
	// --- daily_preferences / daily_assignments (spec 8.1) --------------------------------------
	public static final SqlColumn<AssignmentRow, UUID> ASSIGNMENT_USER_ID = DAILY_ASSIGNMENTS.column("user_id", UUID_TYPE, AssignmentRow::userId, SqlColumnBuilder::notNull);
	public static final SqlColumn<AssignmentRow, LocalDate> ASSIGNMENT_DATE = DAILY_ASSIGNMENTS.column("date", SqlTypes.LOCAL_DATE, AssignmentRow::date, SqlColumnBuilder::notNull);
	public static final SqlColumn<AssignmentRow, Integer> ASSIGNMENT_DIFFICULTY = DAILY_ASSIGNMENTS.column("difficulty", SqlTypes.INTEGER, AssignmentRow::difficulty, SqlColumnBuilder::notNull);
	private Schema() {}
	
	/** A row of {@code server_meta}: an arbitrary key/value pair, including the schema version. */
	public record ServerMetaRow(@NonNull String key, @NonNull String value) {}
	
	/**
	 * A row of {@code link_codes}. Purely a persistence shape - callers only ever see the pieces they
	 * asked for (server-spec 6.4), never this record.
	 */
	public record LinkCodeRow(@NonNull String code, @NonNull UUID userId, @NonNull Instant expiresAt, @Nullable Instant consumedAt, @NonNull Instant createdAt) {}
	
	/** A row of {@code auth_challenges}, single-use and deleted on consumption (server-spec 12). */
	public record AuthChallengeRow(byte @NonNull [] nonce, byte @NonNull [] publicKey, @NonNull Instant expiresAt) {
		
		public AuthChallengeRow {
			// Records copy the array reference, so without this a caller could mutate stored bytes.
			nonce = nonce.clone();
			publicKey = publicKey.clone();
		}
		
		@Override
		public byte @NonNull [] nonce() {
			return this.nonce.clone();
		}
		
		@Override
		public byte @NonNull [] publicKey() {
			return this.publicKey.clone();
		}
	}
	
	/**
	 * A row of {@code daily_leaderboard}. The write path keeps a per-tier best time under a composite
	 * conflict key with a {@code least()} merge that the query builder's generic upsert cannot express
	 * (it only supports a single conflict column and always assigns {@code EXCLUDED} verbatim), so
	 * {@code record()} stays raw SQL; reads and pruning use this row like any other table.
	 */
	public record LeaderboardRow(@NonNull LocalDate date, int difficulty, @NonNull UUID userId, long elapsedMs, int attempts, int hintsUsed) {}
	
	/**
	 * A row of {@code match_participants}. The domain type {@code MatchParticipant} additionally carries
	 * a display name denormalised from a join with {@code users}, which is not a column of this table, so
	 * it cannot be the table's entity type; this is the bare row the table actually stores.
	 */
	public record ParticipantRow(@NonNull UUID matchId, @NonNull UUID userId, @NonNull Instant joinedAt, @Nullable MatchResult result) {}
	
	/**
	 * A row of {@code currency_ledger} (server-spec 9a). {@code id} is DB-generated identity; the query
	 * builder's entity insert has no way to omit a column, so {@code append()} stays raw SQL and relies
	 * on {@code RETURNING} to read the generated id back, same as the UUID primary keys elsewhere.
	 */
	public record LedgerRow(long id, @NonNull UUID userId, int delta, @NonNull LedgerReason reason, @Nullable UUID matchId, @NonNull Instant createdAt) {}
	
	/** A row of {@code daily_preferences}. {@code updatedAt} comes from the application clock. */
	public record PreferenceRow(@NonNull UUID userId, int difficulty, @NonNull Instant updatedAt) {}
	
	/** A row of {@code daily_assignments}. */
	public record AssignmentRow(@NonNull UUID userId, @NonNull LocalDate date, int difficulty) {}
}
