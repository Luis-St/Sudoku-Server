package net.luis.sudoku.db.schema;

import net.luis.sudoku.currency.LedgerReason;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.match.*;
import net.luis.sudoku.permission.Role;
import net.luis.utils.io.database.SqlReferentialAction;
import net.luis.utils.io.database.table.*;
import net.luis.utils.io.database.type.SqlType;
import net.luis.utils.io.database.type.SqlTypes;
import net.luis.utils.io.database.type.parameter.SqlParameter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The database schema as LUtils {@link SqlTable} and {@link SqlColumn} definitions (server-spec 5).
 * <p>
 * This is the single source of truth for the physical schema: {@link SchemaMigration} creates the tables
 * straight from these definitions, and repositories query through them via the LUtils query builder, so
 * a rename cannot leave the two out of step - which is exactly what hand-written SQL strings on both
 * sides used to allow. A column's type, nullability, default, uniqueness, identity, keys and foreign
 * keys are all declared here and nowhere else.
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
		SqlTypes.TEXT.map(Role.class, Role::name, Role::valueOf);
	private static final SqlType<KeyAlgorithm> KEY_ALGORITHM_TYPE =
		SqlTypes.TEXT.map(KeyAlgorithm.class, KeyAlgorithm::name, KeyAlgorithm::valueOf);
	private static final SqlType<MatchMode> MATCH_MODE_TYPE =
		SqlTypes.TEXT.map(MatchMode.class, MatchMode::name, MatchMode::valueOf);
	private static final SqlType<MatchState> MATCH_STATE_TYPE =
		SqlTypes.TEXT.map(MatchState.class, MatchState::name, MatchState::valueOf);
	private static final SqlType<Variant> VARIANT_TYPE =
		SqlTypes.TEXT.map(Variant.class, Variant::name, Variant::valueOf);
	private static final SqlType<GridSize> GRID_SIZE_TYPE =
		SqlTypes.INTEGER.map(GridSize.class, GridSize::n, GridSize::ofEdgeLength);
	private static final SqlType<Difficulty> DIFFICULTY_TYPE =
		SqlTypes.INTEGER.map(Difficulty.class, Difficulty::index, Difficulty::ofIndex);
	private static final SqlType<DailyOutcome> DAILY_OUTCOME_TYPE =
		SqlTypes.TEXT.map(DailyOutcome.class, DailyOutcome::name, DailyOutcome::valueOf);
	private static final SqlType<LedgerReason> LEDGER_REASON_TYPE =
		SqlTypes.TEXT.map(LedgerReason.class, LedgerReason::name, LedgerReason::valueOf);
	/** Nullable: a match still running has no {@code end_reason}. */
	private static final SqlType<EndReason> END_REASON_TYPE =
		SqlTypes.TEXT.map(EndReason.class, reason -> reason == null ? null : reason.name(), name -> name == null ? null : EndReason.valueOf(name));
	/** Nullable: a participant has no {@code result} while the match is running. */
	private static final SqlType<MatchResult> MATCH_RESULT_TYPE =
		SqlTypes.TEXT.map(MatchResult.class, result -> result == null ? null : result.name(), name -> name == null ? null : MatchResult.valueOf(name));
	/**
	 * Entity ids as the native Postgres {@code uuid} type, matching the {@code UUID PRIMARY KEY} columns
	 * {@link SchemaMigration} creates. Must be the literal {@link SqlTypes#UUID} singleton, not a lookalike built
	 * via {@code .map()}: the Postgres dialect's {@code Types.OTHER} bind/read override
	 * ({@code setObject(..., Types.OTHER)} / {@code getObject(..., UUID.class)}) is registered against
	 * that exact instance, not against its structure - an equivalent type falls back to generic
	 * {@code FIXED_STRING} binding and Postgres then rejects it ("column is of type uuid but expression
	 * is of type character"). An earlier revision built a CHAR(36) lookalike to dodge a *migration
	 * introspection* bug in LUtils 10.4.0-beta.2 (fixed in beta.3); it broke query execution instead, and
	 * the schema now goes through {@code SqlMigrationRunner} with the singleton and no workaround at all.
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
	 * Holds the server id, and is created by {@link SchemaMigration} like every other table - the schema
	 * version it used to hold now lives in the migration runner's own bookkeeping.
	 */
	public static final SqlTable<ServerMetaRow> SERVER_META = SqlTable.create(ServerMetaRow.class, "server_meta");
	public static final SqlColumn<ServerMetaRow, String> META_KEY = SERVER_META.column("key", SqlTypes.TEXT, ServerMetaRow::key, col -> col.primaryKey().notNull());
	public static final SqlColumn<ServerMetaRow, String> META_VALUE = SERVER_META.column("value", SqlTypes.TEXT, ServerMetaRow::value, SqlColumnBuilder::notNull);
	public static final SqlTable<User> USERS = SqlTable.create(User.class, "users");
	
	// --- users ---------------------------------------------------------------------------------
	public static final SqlColumn<User, UUID> USER_ID = USERS.column("id", UUID_TYPE, User::id, col -> col.primaryKey().notNull());
	public static final SqlColumn<User, String> USER_DISPLAY_NAME = USERS.column("display_name", SqlTypes.TEXT, User::displayName, col -> col.notNull().unique());
	public static final SqlColumn<User, Role> USER_ROLE = USERS.column("role", ROLE_TYPE, User::role, SqlColumnBuilder::notNull);
	public static final SqlColumn<User, Instant> USER_CREATED_AT = USERS.column("created_at", TIMESTAMP, User::createdAt, SqlColumnBuilder::notNull);
	/** Kicked users are revoked, never deleted: their historical results are retained (spec 7.2). */
	public static final SqlColumn<User, Boolean> USER_REVOKED = USERS.column("revoked", SqlTypes.BOOLEAN, User::revoked, col -> col.notNull().defaultValue(false));
	/** Nullable: not every user has set a recovery email. Unique so a verified address identifies one account. */
	public static final SqlColumn<User, String> USER_EMAIL = USERS.column("email", SqlTypes.STRING.configure(SqlParameter.length(254)), User::email, col -> col.unique());
	public static final SqlColumn<User, Boolean> USER_EMAIL_VERIFIED = USERS.column("email_verified", SqlTypes.BOOLEAN, User::emailVerified, col -> col.notNull().defaultValue(false));
	public static final SqlTable<Device> DEVICES = SqlTable.create(Device.class, "devices");
	
	// --- devices -------------------------------------------------------------------------------
	public static final SqlColumn<Device, UUID> DEVICE_ID = DEVICES.column("id", UUID_TYPE, Device::id, col -> col.primaryKey().notNull());
	public static final SqlColumn<Device, UUID> DEVICE_USER_ID = DEVICES.column("user_id", UUID_TYPE, Device::userId, SqlColumnBuilder::notNull);
	public static final SqlColumn<Device, byte[]> DEVICE_PUBLIC_KEY = DEVICES.column("public_key", SqlTypes.LARGE_BYTES, Device::publicKey, col -> col.notNull().unique());
	/** Devices need not agree: Android uses ECDSA P-256, other clients may use Ed25519 (spec 5). */
	public static final SqlColumn<Device, KeyAlgorithm> DEVICE_KEY_ALGORITHM = DEVICES.column("key_algorithm", KEY_ALGORITHM_TYPE, Device::keyAlgorithm, SqlColumnBuilder::notNull);
	public static final SqlColumn<Device, String> DEVICE_LABEL = DEVICES.column("label", SqlTypes.TEXT, Device::label, SqlColumnBuilder::notNull);
	public static final SqlColumn<Device, Instant> DEVICE_CREATED_AT = DEVICES.column("created_at", TIMESTAMP, Device::createdAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<Device, Instant> DEVICE_LAST_SEEN_AT = DEVICES.column("last_seen_at", TIMESTAMP, Device::lastSeenAt);
	public static final SqlColumn<Device, Boolean> DEVICE_REVOKED = DEVICES.column("revoked", SqlTypes.BOOLEAN, Device::revoked, col -> col.notNull().defaultValue(false));
	/** Which revocations a reinstatement undoes (spec 7.2) - see {@link Device#revokedByKick()}. */
	public static final SqlColumn<Device, Boolean> DEVICE_REVOKED_BY_KICK = DEVICES.column("revoked_by_kick", SqlTypes.BOOLEAN, Device::revokedByKick, col -> col.notNull().defaultValue(false));
	public static final SqlTable<Invite> INVITES = SqlTable.create(Invite.class, "invites");
	
	// --- invites -------------------------------------------------------------------------------
	public static final SqlColumn<Invite, String> INVITE_CODE = INVITES.column("code", SqlTypes.TEXT, Invite::code, col -> col.primaryKey().notNull());
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
	public static final SqlColumn<LinkCodeRow, String> LINK_CODE = LINK_CODES.column("code", SqlTypes.TEXT, LinkCodeRow::code, col -> col.primaryKey().notNull());
	public static final SqlColumn<LinkCodeRow, UUID> LINK_USER_ID = LINK_CODES.column("user_id", UUID_TYPE, LinkCodeRow::userId, SqlColumnBuilder::notNull);
	/** Minutes, not hours: the code lives only as long as it takes to walk to the other device. */
	public static final SqlColumn<LinkCodeRow, Instant> LINK_EXPIRES_AT = LINK_CODES.column("expires_at", TIMESTAMP, LinkCodeRow::expiresAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<LinkCodeRow, Instant> LINK_CONSUMED_AT = LINK_CODES.column("consumed_at", TIMESTAMP, LinkCodeRow::consumedAt);
	public static final SqlColumn<LinkCodeRow, Instant> LINK_CREATED_AT = LINK_CODES.column("created_at", TIMESTAMP, LinkCodeRow::createdAt, SqlColumnBuilder::notNull);
	public static final SqlTable<EmailVerificationRow> EMAIL_VERIFICATIONS = SqlTable.create(EmailVerificationRow.class, "email_verifications");
	
	// --- email_verifications (account recovery) -------------------------------------------------
	public static final SqlColumn<EmailVerificationRow, String> EMAIL_VERIFICATION_CODE = EMAIL_VERIFICATIONS.column("code", SqlTypes.STRING.configure(SqlParameter.length(32)), EmailVerificationRow::code, col -> col.primaryKey().notNull());
	public static final SqlColumn<EmailVerificationRow, UUID> EMAIL_VERIFICATION_USER_ID = EMAIL_VERIFICATIONS.column("user_id", UUID_TYPE, EmailVerificationRow::userId, SqlColumnBuilder::notNull);
	public static final SqlColumn<EmailVerificationRow, String> EMAIL_VERIFICATION_EMAIL = EMAIL_VERIFICATIONS.column("email", SqlTypes.STRING.configure(SqlParameter.length(254)), EmailVerificationRow::email, SqlColumnBuilder::notNull);
	/** Minutes, not hours: long enough to read an email, short enough to keep guessing a 6-digit code hopeless. */
	public static final SqlColumn<EmailVerificationRow, Instant> EMAIL_VERIFICATION_EXPIRES_AT = EMAIL_VERIFICATIONS.column("expires_at", TIMESTAMP, EmailVerificationRow::expiresAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<EmailVerificationRow, Instant> EMAIL_VERIFICATION_CONSUMED_AT = EMAIL_VERIFICATIONS.column("consumed_at", TIMESTAMP, EmailVerificationRow::consumedAt);
	public static final SqlColumn<EmailVerificationRow, Instant> EMAIL_VERIFICATION_CREATED_AT = EMAIL_VERIFICATIONS.column("created_at", TIMESTAMP, EmailVerificationRow::createdAt, SqlColumnBuilder::notNull);
	public static final SqlTable<RecoveryCodeRow> RECOVERY_CODES = SqlTable.create(RecoveryCodeRow.class, "recovery_codes");
	
	// --- recovery_codes (account recovery) ---------------------------------------------------------
	public static final SqlColumn<RecoveryCodeRow, String> RECOVERY_CODE = RECOVERY_CODES.column("code", SqlTypes.STRING.configure(SqlParameter.length(32)), RecoveryCodeRow::code, col -> col.primaryKey().notNull());
	public static final SqlColumn<RecoveryCodeRow, UUID> RECOVERY_USER_ID = RECOVERY_CODES.column("user_id", UUID_TYPE, RecoveryCodeRow::userId, SqlColumnBuilder::notNull);
	/** Minutes, not hours: whoever redeems it takes over the account immediately (spec: account recovery). */
	public static final SqlColumn<RecoveryCodeRow, Instant> RECOVERY_EXPIRES_AT = RECOVERY_CODES.column("expires_at", TIMESTAMP, RecoveryCodeRow::expiresAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<RecoveryCodeRow, Instant> RECOVERY_CONSUMED_AT = RECOVERY_CODES.column("consumed_at", TIMESTAMP, RecoveryCodeRow::consumedAt);
	public static final SqlColumn<RecoveryCodeRow, Instant> RECOVERY_CREATED_AT = RECOVERY_CODES.column("created_at", TIMESTAMP, RecoveryCodeRow::createdAt, SqlColumnBuilder::notNull);
	public static final SqlTable<Session> SESSIONS = SqlTable.create(Session.class, "sessions");
	// --- sessions ------------------------------------------------------------------------------
	public static final SqlColumn<Session, String> SESSION_TOKEN = SESSIONS.column("token", SqlTypes.TEXT, Session::token, col -> col.primaryKey().notNull());
	/**
	 * Not unique: a session belongs to a device, and a user may have several devices signed in at once
	 * (spec 6.2). It was unique until schema v9, which is what made linking a second device sign the
	 * first one out - see {@link SchemaMigration.PerDeviceSessions}.
	 */
	public static final SqlColumn<Session, UUID> SESSION_USER_ID = SESSIONS.column("user_id", UUID_TYPE, Session::userId, SqlColumnBuilder::notNull);
	/** UNIQUE enforces the one-session-per-device rule (spec 6.2) in the schema, not just in code. */
	public static final SqlColumn<Session, UUID> SESSION_DEVICE_ID = SESSIONS.column("device_id", UUID_TYPE, Session::deviceId, col -> col.notNull().unique());
	public static final SqlColumn<Session, Instant> SESSION_ISSUED_AT = SESSIONS.column("issued_at", TIMESTAMP, Session::issuedAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<Session, Instant> SESSION_EXPIRES_AT = SESSIONS.column("expires_at", TIMESTAMP, Session::expiresAt, SqlColumnBuilder::notNull);
	/**
	 * The token this row replaced, kept only until the next re-issue, so a client still holding it is
	 * told {@code SESSION_SUPERSEDED} rather than a bare {@code UNAUTHORIZED}.
	 * <p>
	 * It lives on the replacing row instead of as a tombstone row of its own precisely because
	 * {@link #SESSION_DEVICE_ID} is unique: a device has exactly one row, whatever has happened to it.
	 * Nullable, and null for every device that has only ever signed in once.
	 */
	public static final SqlColumn<Session, String> SESSION_SUPERSEDED_TOKEN = SESSIONS.column("superseded_token", SqlTypes.TEXT, Session::supersededToken);
	/** When {@link #SESSION_SUPERSEDED_TOKEN} stopped being valid. Null exactly when that column is null. */
	public static final SqlColumn<Session, Instant> SESSION_SUPERSEDED_AT = SESSIONS.column("superseded_at", TIMESTAMP, Session::supersededAt);
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
	/** Banked at every 7th consecutive day, capped at {@link Streak#MAX_RESTORE_POINTS}; spent to repair a gap. */
	public static final SqlColumn<Streak, Integer> STREAK_RESTORE_POINTS = STREAKS.column("restore_points", SqlTypes.INTEGER, Streak::restorePoints, col -> col.notNull().defaultValue(0));
	public static final SqlTable<StatsEntry> STATS = SqlTable.create(StatsEntry.class, "stats");
	public static final SqlColumn<StatsEntry, UUID> STATS_USER_ID = STATS.column("user_id", UUID_TYPE, StatsEntry::userId, col -> col.primaryKey().notNull());
	// --- stats ---------------------------------------------------------------------------------
	public static final SqlColumn<StatsEntry, Integer> STATS_SIZE = STATS.column("size", SqlTypes.INTEGER, StatsEntry::size, col -> col.primaryKey().notNull());
	public static final SqlColumn<StatsEntry, String> STATS_VARIANT = STATS.column("variant", SqlTypes.TEXT, StatsEntry::variant, col -> col.primaryKey().notNull());
	/** 6 = LISA, single-player only, but it does appear in a player's own statistics. */
	public static final SqlColumn<StatsEntry, Integer> STATS_DIFFICULTY = STATS.column("difficulty", SqlTypes.INTEGER, StatsEntry::difficulty, col -> col.primaryKey().notNull());
	public static final SqlColumn<StatsEntry, Integer> STATS_GAMES_PLAYED = STATS.column("games_played", SqlTypes.INTEGER, StatsEntry::gamesPlayed, col -> col.notNull().defaultValue(0));
	public static final SqlColumn<StatsEntry, Integer> STATS_SOLVED = STATS.column("solved", SqlTypes.INTEGER, StatsEntry::solved, col -> col.notNull().defaultValue(0));
	public static final SqlColumn<StatsEntry, Integer> STATS_FAILED = STATS.column("failed", SqlTypes.INTEGER, StatsEntry::failed, col -> col.notNull().defaultValue(0));
	public static final SqlColumn<StatsEntry, Long> STATS_BEST_TIME_MS = STATS.column("best_time_ms", SqlTypes.LONG, StatsEntry::bestTimeMs);
	/** The running total, not the mean: an average cannot be merged incrementally without it. */
	public static final SqlColumn<StatsEntry, Long> STATS_TOTAL_TIME_MS = STATS.column("total_time_ms", SqlTypes.LONG, StatsEntry::totalTimeMs, col -> col.notNull().defaultValue(0L));
	public static final SqlColumn<StatsEntry, Integer> STATS_HINTS_USED = STATS.column("hints_used", SqlTypes.INTEGER, StatsEntry::hintsUsed, col -> col.notNull().defaultValue(0));
	public static final SqlTable<LeaderboardRow> DAILY_LEADERBOARD = SqlTable.create(LeaderboardRow.class, "daily_leaderboard");
	public static final SqlColumn<LeaderboardRow, LocalDate> LEADERBOARD_DATE = DAILY_LEADERBOARD.column("date", SqlTypes.LOCAL_DATE, LeaderboardRow::date, col -> col.primaryKey().notNull());
	// --- daily_leaderboard ---------------------------------------------------------------------
	public static final SqlColumn<LeaderboardRow, Integer> LEADERBOARD_DIFFICULTY = DAILY_LEADERBOARD.column("difficulty", SqlTypes.INTEGER, LeaderboardRow::difficulty, col -> col.primaryKey().notNull());
	public static final SqlColumn<LeaderboardRow, UUID> LEADERBOARD_USER_ID = DAILY_LEADERBOARD.column("user_id", UUID_TYPE, LeaderboardRow::userId, col -> col.primaryKey().notNull());
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
	/** LISA (15) is rejected for every multiplayer mode (spec 10.1). */
	public static final SqlColumn<Match, Difficulty> MATCH_DIFFICULTY = MATCHES.column("difficulty", DIFFICULTY_TYPE, Match::difficulty, SqlColumnBuilder::notNull);
	/** The PuzzleKey seed; the grid itself is never stored or sent (spec 1). */
	public static final SqlColumn<Match, Long> MATCH_SEED = MATCHES.column("seed", SqlTypes.LONG, Match::seed, SqlColumnBuilder::notNull);
	/**
	 * The generated grid's givens, {@code GivensCodec}-encoded, so a match never has to be generated twice.
	 * <p>
	 * Nullable, and nullable for good: every match created before this column existed has none, and
	 * {@code MatchService.liveFor} falls back to regenerating from the key exactly as it always did. New
	 * matches always carry it, which is what lets {@code GET /matches/{id}}, rehydration and reconnect all
	 * rebuild the board from a backtracking solve rather than from a full generate-dig-rate search.
	 */
	public static final SqlColumn<Match, String> MATCH_GIVENS = MATCHES.column("givens", SqlTypes.TEXT, Match::givens);
	public static final SqlColumn<Match, Boolean> MATCH_LIVES_ENABLED = MATCHES.column("lives_enabled", SqlTypes.BOOLEAN, Match::livesEnabled, col -> col.notNull().defaultValue(false));
	/** Defaults to true, unlike lives: a match created before this column existed had hints available, since nothing stopped them. */
	public static final SqlColumn<Match, Boolean> MATCH_HINTS_ENABLED = MATCHES.column("hints_enabled", SqlTypes.BOOLEAN, Match::hintsEnabled, col -> col.notNull().defaultValue(true));
	/** No minimum, no maximum (spec 9a.3). 0 means anyone may join. */
	public static final SqlColumn<Match, Integer> MATCH_STAKE = MATCHES.column("stake", SqlTypes.INTEGER, Match::stake, col -> col.notNull().defaultValue(0));
	public static final SqlColumn<Match, String> MATCH_INVITE_TOKEN = MATCHES.column("invite_token", SqlTypes.TEXT, Match::inviteToken, SqlColumnBuilder::notNull);
	public static final SqlColumn<Match, UUID> MATCH_WINNER_ID = MATCHES.column("winner_id", UUID_TYPE, Match::winnerId);
	public static final SqlColumn<Match, EndReason> MATCH_END_REASON = MATCHES.column("end_reason", END_REASON_TYPE, Match::endReason);
	public static final SqlColumn<Match, Instant> MATCH_CREATED_AT = MATCHES.column("created_at", TIMESTAMP, Match::createdAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<Match, Instant> MATCH_STARTED_AT = MATCHES.column("started_at", TIMESTAMP, Match::startedAt);
	public static final SqlColumn<Match, Instant> MATCH_ENDED_AT = MATCHES.column("ended_at", TIMESTAMP, Match::endedAt);
	public static final SqlTable<ParticipantRow> MATCH_PARTICIPANTS = SqlTable.create(ParticipantRow.class, "match_participants");
	public static final SqlColumn<ParticipantRow, UUID> PARTICIPANT_MATCH_ID = MATCH_PARTICIPANTS.column("match_id", UUID_TYPE, ParticipantRow::matchId, col -> col.primaryKey().notNull());
	public static final SqlColumn<ParticipantRow, UUID> PARTICIPANT_USER_ID = MATCH_PARTICIPANTS.column("user_id", UUID_TYPE, ParticipantRow::userId, col -> col.primaryKey().notNull());
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
	/**
	 * Defaults to tier 5 of fifteen (spec 8.1), not the 3 it was when there were only six tiers: under the
	 * fifteen-band scale tier 3 is hidden singles along a line and is over in a minute, which is not what a
	 * player who never touched the setting should be handed every day. Only new rows are affected - a player
	 * who already has a preference keeps it, and there is no CHECK constraint on the column to alter, so this
	 * needs no migration.
	 */
	public static final SqlColumn<PreferenceRow, Integer> PREFERENCE_DIFFICULTY = DAILY_PREFERENCES.column("daily_difficulty", SqlTypes.INTEGER, PreferenceRow::difficulty, col -> col.notNull().defaultValue(5));
	public static final SqlColumn<PreferenceRow, Instant> PREFERENCE_UPDATED_AT = DAILY_PREFERENCES.column("updated_at", TIMESTAMP, PreferenceRow::updatedAt, SqlColumnBuilder::notNull);
	/**
	 * The difficulty locked in for one player on one date.
	 * <p>
	 * Written on the first {@code /daily} request of a date and never updated, which is the entire
	 * mechanism behind "a preference change takes effect from the next day only".
	 */
	public static final SqlTable<AssignmentRow> DAILY_ASSIGNMENTS = SqlTable.create(AssignmentRow.class, "daily_assignments");
	// --- daily_preferences / daily_assignments (spec 8.1) --------------------------------------
	public static final SqlColumn<AssignmentRow, UUID> ASSIGNMENT_USER_ID = DAILY_ASSIGNMENTS.column("user_id", UUID_TYPE, AssignmentRow::userId, col -> col.primaryKey().notNull());
	public static final SqlColumn<AssignmentRow, LocalDate> ASSIGNMENT_DATE = DAILY_ASSIGNMENTS.column("date", SqlTypes.LOCAL_DATE, AssignmentRow::date, col -> col.primaryKey().notNull());
	public static final SqlColumn<AssignmentRow, Integer> ASSIGNMENT_DIFFICULTY = DAILY_ASSIGNMENTS.column("difficulty", SqlTypes.INTEGER, AssignmentRow::difficulty, SqlColumnBuilder::notNull);
	
	/**
	 * The last heartbeat each signed-in client sent (feature-spec 9.7's online status).
	 * <p>
	 * One row per user, rewritten every heartbeat, and "online" is derived from it rather than stored: a
	 * player counts as online while {@code last_seen_at} is younger than
	 * {@link net.luis.sudoku.config.PresenceConfig#onlineTtlSeconds}. That derivation is the whole reason
	 * presence lives in a table at all - a boolean would have to be un-set by whoever noticed the client
	 * vanished, and nobody reliably notices; a timestamp goes stale on its own, with no cooperation from a
	 * client that has been killed, backgrounded or moved out of coverage.
	 */
	public static final SqlTable<PresenceRow> PRESENCE = SqlTable.create(PresenceRow.class, "presence");
	
	// --- presence ------------------------------------------------------------------------------
	public static final SqlColumn<PresenceRow, UUID> PRESENCE_USER_ID = PRESENCE.column("user_id", UUID_TYPE, PresenceRow::userId, col -> col.primaryKey().notNull());
	public static final SqlColumn<PresenceRow, Instant> PRESENCE_LAST_SEEN_AT = PRESENCE.column("last_seen_at", TIMESTAMP, PresenceRow::lastSeenAt, SqlColumnBuilder::notNull);
	
	/**
	 * A pending "come play with me" addressed at one player (feature-spec 9.7).
	 * <p>
	 * Stored, unlike the push this replaced, because there is no longer a socket to push down - and short-lived
	 * ({@link net.luis.sudoku.config.PresenceConfig#matchRequestTtlSeconds}), because a request that arrives
	 * after the match it names has been abandoned is worse than one that never arrives. The mode, stake and
	 * invite token are deliberately <em>not</em> copied here: they belong to {@code matches}, and the row is
	 * joined against it when the request is served, so a request can never carry a stale token.
	 */
	public static final SqlTable<MatchRequestRow> MATCH_REQUESTS = SqlTable.create(MatchRequestRow.class, "match_requests");
	
	// --- match_requests ------------------------------------------------------------------------
	public static final SqlColumn<MatchRequestRow, UUID> REQUEST_ID = MATCH_REQUESTS.column("id", UUID_TYPE, MatchRequestRow::id, col -> col.primaryKey().notNull());
	public static final SqlColumn<MatchRequestRow, UUID> REQUEST_TARGET_USER_ID = MATCH_REQUESTS.column("target_user_id", UUID_TYPE, MatchRequestRow::targetUserId, SqlColumnBuilder::notNull);
	public static final SqlColumn<MatchRequestRow, UUID> REQUEST_MATCH_ID = MATCH_REQUESTS.column("match_id", UUID_TYPE, MatchRequestRow::matchId, SqlColumnBuilder::notNull);
	public static final SqlColumn<MatchRequestRow, UUID> REQUEST_FROM_USER_ID = MATCH_REQUESTS.column("from_user_id", UUID_TYPE, MatchRequestRow::fromUserId, SqlColumnBuilder::notNull);
	public static final SqlColumn<MatchRequestRow, Instant> REQUEST_EXPIRES_AT = MATCH_REQUESTS.column("expires_at", TIMESTAMP, MatchRequestRow::expiresAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<MatchRequestRow, Instant> REQUEST_CREATED_AT = MATCH_REQUESTS.column("created_at", TIMESTAMP, MatchRequestRow::createdAt, SqlColumnBuilder::notNull);
	
	/**
	 * The games already folded into {@code stats}, so folding one twice is impossible (server-spec 9).
	 * <p>
	 * {@code stats} is a table of counters that only ever increment, which makes every write to it
	 * dangerous to repeat: a client that uploads a finished game and loses the response has no way to tell
	 * a request that never arrived from one the server committed, and retrying - which is exactly what an
	 * offline queue does - would count the game twice, permanently. So the client names each game with an
	 * id it generates once, at the moment the game ends, and this table remembers the ids that have been
	 * accepted. A second upload of the same id is dropped rather than added.
	 * <p>
	 * The id is only unique per player: it is generated on a device, and two devices agreeing on a UUID is
	 * not something one player's stats should depend on another player's client avoiding. Hence the
	 * composite key.
	 */
	public static final SqlTable<RecordedGameRow> RECORDED_GAMES = SqlTable.create(RecordedGameRow.class, "recorded_games");
	
	// --- recorded_games ------------------------------------------------------------------------
	public static final SqlColumn<RecordedGameRow, UUID> RECORDED_USER_ID = RECORDED_GAMES.column("user_id", UUID_TYPE, RecordedGameRow::userId, col -> col.primaryKey().notNull());
	/** Generated by the client, once, when the game ends - never here, or it could not identify a retry. */
	public static final SqlColumn<RecordedGameRow, UUID> RECORDED_GAME_ID = RECORDED_GAMES.column("game_id", UUID_TYPE, RecordedGameRow::gameId, col -> col.primaryKey().notNull());
	/** When the server accepted it, which is what the rollover prunes on - not when it was played. */
	public static final SqlColumn<RecordedGameRow, Instant> RECORDED_AT = RECORDED_GAMES.column("recorded_at", TIMESTAMP, RecordedGameRow::recordedAt, SqlColumnBuilder::notNull);
	
	/**
	 * The pre-generated puzzles waiting to be handed out - {@code PuzzleQueue}'s pool, made durable.
	 * <p>
	 * The pool used to be a map in one process, which meant a restart threw away every puzzle it had paid
	 * to generate and a second instance kept a pool of its own. Neither is affordable now that the queue
	 * sits in front of every single-player game start as well as every match: the dear bands cost seconds
	 * apiece, so the pool is worth keeping across a deploy and worth sharing between containers.
	 * <p>
	 * <strong>A row is a key plus its givens, and no solution.</strong> {@code PuzzleGenerator.fromGivens}
	 * derives the solution with a backtracking solve in milliseconds, so storing it would buy nothing and
	 * cost the one thing a derived column always costs - a second field that can disagree with the first.
	 * The same reasoning is written out on {@code PuzzleResponse}, which sends the givens and no solution
	 * for exactly this reason.
	 * <p>
	 * <strong>{@code gen_version} is stored, not assumed.</strong> A row written by a previous generator
	 * version names a puzzle this build would no longer produce from the same key, and handing one out
	 * would put a client on a grid its own shared-core disagrees with. Every claim filters on the current
	 * version and the rest are deleted outright, which is why the column is here rather than derived from
	 * "whatever this build is".
	 */
	public static final SqlTable<PuzzlePoolRow> PUZZLE_POOL = SqlTable.create(PuzzlePoolRow.class, "puzzle_pool");
	
	// --- puzzle_pool ---------------------------------------------------------------------------
	/** DB-generated identity, so a claim can name exactly the rows it locked when it deletes them. */
	public static final SqlColumn<PuzzlePoolRow, Long> POOL_ID = PUZZLE_POOL.column("id", SqlTypes.LONG, PuzzlePoolRow::id, col -> col.primaryKey().notNull().autoIncrement());
	public static final SqlColumn<PuzzlePoolRow, Integer> POOL_GEN_VERSION = PUZZLE_POOL.column("gen_version", SqlTypes.INTEGER, PuzzlePoolRow::genVersion, SqlColumnBuilder::notNull);
	public static final SqlColumn<PuzzlePoolRow, GridSize> POOL_SIZE = PUZZLE_POOL.column("size", GRID_SIZE_TYPE, PuzzlePoolRow::size, SqlColumnBuilder::notNull);
	public static final SqlColumn<PuzzlePoolRow, Variant> POOL_VARIANT = PUZZLE_POOL.column("variant", VARIANT_TYPE, PuzzlePoolRow::variant, SqlColumnBuilder::notNull);
	/** Lisa (15) is pooled like every other band: the queue serves single-player content too. */
	public static final SqlColumn<PuzzlePoolRow, Difficulty> POOL_DIFFICULTY = PUZZLE_POOL.column("difficulty", DIFFICULTY_TYPE, PuzzlePoolRow::difficulty, SqlColumnBuilder::notNull);
	public static final SqlColumn<PuzzlePoolRow, Long> POOL_SEED = PUZZLE_POOL.column("seed", SqlTypes.LONG, PuzzlePoolRow::seed, SqlColumnBuilder::notNull);
	/** {@code GivensCodec}-encoded, the same string {@link #MATCH_GIVENS} stores and the wire carries. */
	public static final SqlColumn<PuzzlePoolRow, String> POOL_GIVENS = PUZZLE_POOL.column("givens", SqlTypes.TEXT, PuzzlePoolRow::givens, SqlColumnBuilder::notNull);
	/** Diagnostic only: how old a pool is answers "did the workers stall", which nothing else does. */
	public static final SqlColumn<PuzzlePoolRow, Instant> POOL_CREATED_AT = PUZZLE_POOL.column("created_at", TIMESTAMP, PuzzlePoolRow::createdAt, SqlColumnBuilder::notNull);
	
	/**
	 * One player's progress through the learn area, one row per finished exercise.
	 * <p>
	 * The learn area is local first and works with the phone in flight mode, so this table is a copy of what
	 * a device already holds rather than the place the progress lives. What it buys is a second device: the
	 * same account picking up on a tablet sees the techniques it has already mastered instead of an empty
	 * wiki.
	 * <p>
	 * <strong>Only finished exercises are stored.</strong> Locked and open are derived from the rows around
	 * them on the client, so writing them here would give the same fact a second place to be wrong, and the
	 * rows would have to be created for all forty-one techniques on the day an account is made.
	 * <p>
	 * <strong>A merge keeps the better state, never the newer row.</strong> Two devices are both allowed to
	 * work offline, so a stale {@code PARTIAL} arriving after a {@code SOLVED} must not overwrite it and
	 * silently un-earn an achievement the player has already been shown. {@link #LEARN_UPDATED_AT} is kept
	 * for diagnostics rather than as the tie-breaker, which is exactly what it must not be.
	 */
	public static final SqlTable<LearnProgressRow> LEARN_PROGRESS = SqlTable.create(LearnProgressRow.class, "learn_progress");
	
	// --- learn_progress ------------------------------------------------------------------------
	public static final SqlColumn<LearnProgressRow, UUID> LEARN_USER_ID = LEARN_PROGRESS.column("user_id", UUID_TYPE, LearnProgressRow::userId, col -> col.primaryKey().notNull());
	/** The technique's enum name, which is a stable identifier the client and the core already share. */
	public static final SqlColumn<LearnProgressRow, String> LEARN_TECHNIQUE = LEARN_PROGRESS.column("technique", SqlTypes.TEXT, LearnProgressRow::technique, col -> col.primaryKey().notNull());
	public static final SqlColumn<LearnProgressRow, Integer> LEARN_LEVEL = LEARN_PROGRESS.column("level", SqlTypes.INTEGER, LearnProgressRow::level, col -> col.primaryKey().notNull());
	public static final SqlColumn<LearnProgressRow, Integer> LEARN_SUB_LEVEL = LEARN_PROGRESS.column("sub_level", SqlTypes.INTEGER, LearnProgressRow::subLevel, col -> col.primaryKey().notNull());
	/** {@code SOLVED} or {@code PARTIAL}: an exercise finished with the technique, or finished without it. */
	public static final SqlColumn<LearnProgressRow, String> LEARN_STATE = LEARN_PROGRESS.column("state", SqlTypes.TEXT, LearnProgressRow::state, SqlColumnBuilder::notNull);
	/** Diagnostic only. It is deliberately not what a merge compares - see the table's note. */
	public static final SqlColumn<LearnProgressRow, Instant> LEARN_UPDATED_AT = LEARN_PROGRESS.column("updated_at", TIMESTAMP, LearnProgressRow::updatedAt, SqlColumnBuilder::notNull);
	
	private Schema() {}
	
	/** A reference whose rows are deleted with the row they belong to. */
	private static <E, T> void cascade(@NonNull SqlTable<E> table, @NonNull SqlColumn<E, ?> column, @NonNull SqlTable<T> referenced, @NonNull SqlColumn<T, ?> referencedColumn) {
		foreignKey(table, column, referenced, referencedColumn, SqlReferentialAction.CASCADE);
	}
	
	/** A reference that is forgotten when its target goes, leaving the referencing row in place. */
	private static <E, T> void setNull(@NonNull SqlTable<E> table, @NonNull SqlColumn<E, ?> column, @NonNull SqlTable<T> referenced, @NonNull SqlColumn<T, ?> referencedColumn) {
		foreignKey(table, column, referenced, referencedColumn, SqlReferentialAction.SET_NULL);
	}
	
	private static <E, T> void foreignKey(
		@NonNull SqlTable<E> table, @NonNull SqlColumn<E, ?> column, @NonNull SqlTable<T> referenced, @NonNull SqlColumn<T, ?> referencedColumn, @NonNull SqlReferentialAction onDelete
	) {
		// Nothing in this schema ever updates a key, so the update action stays at NO_ACTION.
		table.foreignKey(List.of(column), referenced, List.of(referencedColumn), SqlReferentialAction.NO_ACTION, onDelete);
	}
	
	/**
	 * The keys and references that belong to a table rather than to a single column (server-spec 5).
	 * <p>
	 * Declared here, next to the columns, because {@code CREATE TABLE} is rendered from the
	 * {@link SqlTable} itself - {@link SchemaMigration} names the tables to create, but what they look
	 * like is decided by these definitions.
	 * <p>
	 * The referential actions are how the spec's retention rules are enforced: rows belonging to a user
	 * cascade with the user, while rows that merely <em>mention</em> one - an invite's creator, a match's
	 * winner, a ledger row's match - null the reference instead, because the mentioning row outlives what
	 * it points at. Kicked users are revoked rather than deleted (spec 7.2), so in practice these cascades
	 * fire only for a genuine deletion.
	 */
	static {
		DAILY_RESULTS.uniqueConstraint(RESULT_USER_ID, RESULT_DATE, RESULT_DIFFICULTY, RESULT_ATTEMPT_NO);
		STATS.compositePrimaryKey(STATS_USER_ID, STATS_SIZE, STATS_VARIANT, STATS_DIFFICULTY);
		DAILY_LEADERBOARD.compositePrimaryKey(LEADERBOARD_DATE, LEADERBOARD_DIFFICULTY, LEADERBOARD_USER_ID);
		MATCH_PARTICIPANTS.compositePrimaryKey(PARTICIPANT_MATCH_ID, PARTICIPANT_USER_ID);
		DAILY_ASSIGNMENTS.compositePrimaryKey(ASSIGNMENT_USER_ID, ASSIGNMENT_DATE);
		RECORDED_GAMES.compositePrimaryKey(RECORDED_USER_ID, RECORDED_GAME_ID);
		LEARN_PROGRESS.compositePrimaryKey(LEARN_USER_ID, LEARN_TECHNIQUE, LEARN_LEVEL, LEARN_SUB_LEVEL);
		
		cascade(DEVICES, DEVICE_USER_ID, USERS, USER_ID);
		setNull(INVITES, INVITE_CREATED_BY, USERS, USER_ID);
		setNull(INVITES, INVITE_CONSUMED_BY_DEVICE, DEVICES, DEVICE_ID);
		cascade(LINK_CODES, LINK_USER_ID, USERS, USER_ID);
		cascade(EMAIL_VERIFICATIONS, EMAIL_VERIFICATION_USER_ID, USERS, USER_ID);
		cascade(RECOVERY_CODES, RECOVERY_USER_ID, USERS, USER_ID);
		cascade(SESSIONS, SESSION_USER_ID, USERS, USER_ID);
		cascade(SESSIONS, SESSION_DEVICE_ID, DEVICES, DEVICE_ID);
		cascade(DAILY_RESULTS, RESULT_USER_ID, USERS, USER_ID);
		cascade(STREAKS, STREAK_USER_ID, USERS, USER_ID);
		cascade(STATS, STATS_USER_ID, USERS, USER_ID);
		cascade(DAILY_LEADERBOARD, LEADERBOARD_USER_ID, USERS, USER_ID);
		cascade(MATCHES, MATCH_CREATOR_ID, USERS, USER_ID);
		setNull(MATCHES, MATCH_WINNER_ID, USERS, USER_ID);
		cascade(MATCH_PARTICIPANTS, PARTICIPANT_MATCH_ID, MATCHES, MATCH_ID);
		cascade(MATCH_PARTICIPANTS, PARTICIPANT_USER_ID, USERS, USER_ID);
		cascade(CURRENCY_LEDGER, LEDGER_USER_ID, USERS, USER_ID);
		setNull(CURRENCY_LEDGER, LEDGER_MATCH_ID, MATCHES, MATCH_ID);
		cascade(DAILY_PREFERENCES, PREFERENCE_USER_ID, USERS, USER_ID);
		cascade(DAILY_ASSIGNMENTS, ASSIGNMENT_USER_ID, USERS, USER_ID);
		cascade(PRESENCE, PRESENCE_USER_ID, USERS, USER_ID);
		cascade(LEARN_PROGRESS, LEARN_USER_ID, USERS, USER_ID);
		// Every reference cascades, including the match: a request whose match is gone is not a request
		// that should be delivered, so the row going with it is the behaviour, not a side effect.
		cascade(MATCH_REQUESTS, REQUEST_TARGET_USER_ID, USERS, USER_ID);
		cascade(MATCH_REQUESTS, REQUEST_FROM_USER_ID, USERS, USER_ID);
		cascade(MATCH_REQUESTS, REQUEST_MATCH_ID, MATCHES, MATCH_ID);
		// Cascades with the user like their stats do: the two are one fact, and a dedupe row that outlived
		// the counters it was protecting would only block a re-upload that has nothing to double.
		cascade(RECORDED_GAMES, RECORDED_USER_ID, USERS, USER_ID);
	}
	
	/** A row of {@code server_meta}: an arbitrary key/value pair, including the schema version. */
	public record ServerMetaRow(@NonNull String key, @NonNull String value) {}
	
	/**
	 * A row of {@code link_codes}. Purely a persistence shape - callers only ever see the pieces they
	 * asked for (server-spec 6.4), never this record.
	 */
	public record LinkCodeRow(@NonNull String code, @NonNull UUID userId, @NonNull Instant expiresAt, @Nullable Instant consumedAt, @NonNull Instant createdAt) {}
	
	/**
	 * A row of {@code email_verifications}: a pending claim that {@code userId} owns {@code email},
	 * short-lived and single-use like {@link LinkCodeRow}.
	 */
	public record EmailVerificationRow(@NonNull String code, @NonNull UUID userId, @NonNull String email, @NonNull Instant expiresAt, @Nullable Instant consumedAt, @NonNull Instant createdAt) {}
	
	/**
	 * A row of {@code recovery_codes}. Structurally identical to {@link LinkCodeRow}, but redeemable
	 * without an existing session - that is the whole point of account recovery.
	 */
	public record RecoveryCodeRow(@NonNull String code, @NonNull UUID userId, @NonNull Instant expiresAt, @Nullable Instant consumedAt, @NonNull Instant createdAt) {}
	
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
	 * conflict key with a {@code least()} merge, expressed as an upsert with explicit set clauses
	 * (LUtils 10.4.0-beta.4); reads and pruning use this row like any other table.
	 */
	public record LeaderboardRow(@NonNull LocalDate date, int difficulty, @NonNull UUID userId, long elapsedMs, int attempts, int hintsUsed) {}
	
	/**
	 * A row of {@code match_participants}. The domain type {@code MatchParticipant} additionally carries
	 * a display name denormalised from a join with {@code users}, which is not a column of this table, so
	 * it cannot be the table's entity type; this is the bare row the table actually stores.
	 */
	public record ParticipantRow(@NonNull UUID matchId, @NonNull UUID userId, @NonNull Instant joinedAt, @Nullable MatchResult result) {}
	
	/**
	 * A row of {@code currency_ledger} (server-spec 9a). {@code id} is DB-generated identity, declared
	 * {@code autoIncrement()} above, which is what keeps it out of the value tuple the entity insert
	 * renders - the {@code id} an appended row carries is never read and never written.
	 */
	public record LedgerRow(long id, @NonNull UUID userId, int delta, @NonNull LedgerReason reason, @Nullable UUID matchId, @NonNull Instant createdAt) {}
	
	/** A row of {@code daily_preferences}. {@code updatedAt} comes from the application clock. */
	public record PreferenceRow(@NonNull UUID userId, int difficulty, @NonNull Instant updatedAt) {}
	
	/** A row of {@code daily_assignments}. */
	public record AssignmentRow(@NonNull UUID userId, @NonNull LocalDate date, int difficulty) {}
	
	/**
	 * A row of {@code presence}: when this user's client last said it was running.
	 * <p>
	 * {@code lastSeenAt} always comes from the application {@link java.time.Clock} on the request path,
	 * never from a value the client sent - a client-supplied timestamp would make presence forgeable, and
	 * wrong by however far the two clocks disagree.
	 */
	public record PresenceRow(@NonNull UUID userId, @NonNull Instant lastSeenAt) {}
	
	/** A row of {@code match_requests}: one player has asked {@code targetUserId} to join {@code matchId}. */
	public record MatchRequestRow(
		@NonNull UUID id, @NonNull UUID targetUserId, @NonNull UUID matchId, @NonNull UUID fromUserId, @NonNull Instant expiresAt, @NonNull Instant createdAt
	) {}
	
	/**
	 * A row of {@code recorded_games}: this player's game {@code gameId} has already been folded into
	 * {@code stats}, so a repeat upload of it is a retry and not a second game.
	 */
	public record RecordedGameRow(@NonNull UUID userId, @NonNull UUID gameId, @NonNull Instant recordedAt) {}
	
	/** A row of {@code learn_progress}: one finished exercise of one technique, for one player. */
	public record LearnProgressRow(
		@NonNull UUID userId, @NonNull String technique, int level, int subLevel, @NonNull String state, @NonNull Instant updatedAt
	) {}
	
	/**
	 * A row of {@code puzzle_pool}: one pre-generated puzzle, as everything needed to rebuild it and
	 * nothing more.
	 * <p>
	 * {@code id} is DB-generated identity, so the value passed on insert is never rendered - the same
	 * arrangement as {@link LedgerRow}.
	 */
	public record PuzzlePoolRow(
		long id, int genVersion, @NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty, long seed, @NonNull String givens, @NonNull Instant createdAt
	) {}
}
