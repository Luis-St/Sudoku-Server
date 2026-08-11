package net.luis.sudoku.db.schema;

import net.luis.sudoku.compat.LegacyDifficulty;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.migration.*;
import net.luis.utils.io.database.table.SqlColumn;
import net.luis.utils.io.database.table.SqlTable;
import net.luis.utils.util.Version;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static net.luis.sudoku.db.schema.Schema.*;

/**
 * The database schema as LUtils migrations (server-spec 5), applied by {@code db/Migrations}.
 * <p>
 * The physical schema is derived from {@link Schema}'s {@link SqlTable}/{@link SqlColumn} definitions
 * rather than restated in SQL text: {@link #table} mirrors whatever a column already declares - its type,
 * nullability, default, uniqueness and identity - so a column that exists in {@code Schema} exists in the
 * database with the same shape, and no rename can leave the two out of step. What cannot be read off a
 * column - check constraints, referential actions, composite keys and indexes - is spelled out here,
 * beside the table it belongs to.
 * <p>
 * <strong>Two things the old scripts had that this does not, both blocked upstream.</strong> LUtils
 * renders a {@link net.luis.utils.io.database.condition.SqlCondition} with its values as bind parameters,
 * which is right for a query and impossible for DDL - Postgres rejects
 * {@code CHECK (difficulty BETWEEN $1 AND $2)} with "there is no parameter $1". So the value
 * {@code CHECK} constraints (enum-ish columns restricted to their enum, counters {@code >= 0}, difficulty
 * ranges) and the {@code WHERE} clause of the partial admin index cannot be expressed, and are left out
 * rather than smuggled back in as SQL text. Nothing is unguarded that was not already guarded in the
 * application - the enum columns are written from enums, the counters from computed non-negative values -
 * but the database no longer refuses a bad row on its own. The admin index is a plain two-column index on
 * {@code (role, revoked)} instead, which serves the same lookup and is merely larger. Both come back as a
 * follow-up migration once the renderer inlines literals for DDL conditions.
 * <p>
 * <strong>One migration, not four.</strong> The schema used to arrive as four hand-written scripts
 * ({@code V1__init} … {@code V4__streak_restore}), split the way a deployed database has to receive
 * changes: forward-only, never edited once released. No database was ever deployed from them, so that
 * split recorded a history that does not exist; the four are collapsed into the single initial migration
 * below, which produces exactly the schema the fourth script left behind. From here on the rule that made
 * them separate applies for real - <strong>append a new migration, never edit this one</strong>, because
 * the runner records a checksum per applied version and a changed migration is a changed checksum.
 */
public final class SchemaMigration {
	
	/** Every migration, in version order. */
	public static final List<SqlMigration> ALL = List.of(
		new InitialSchema(), new PresencePolling(), new ReinstatableKicks(), new MatchHintSetting(), new RecordedGames(), new MatchGivens(), new RescaledDifficulties(), new PooledPuzzles(),
		new PerDeviceSessions(), new LearnProgressTable()
	);

	/** The version the schema is at once {@link #ALL} has been applied, reported by {@code /health}. */
	public static final int CURRENT_VERSION = 10;
	
	private SchemaMigration() {}
	
	/**
	 * Adds one column to a table under construction, carrying over every constraint it already declares
	 * on {@link Schema}.
	 * <p>
	 * The primary key is not among them: a migration takes it per table rather than per column, so
	 * {@link #table} passes it separately.
	 *
	 * @param definition The table under construction
	 * @param column The column to add
	 * @param <C> The type of the value held by the column
	 */
	private static <C> void column(@NonNull SqlMigrationTableBuilder definition, @NonNull SqlColumn<?, C> column) {
		definition.column(column, column.type(), options -> {
			if (!column.nullable()) {
				options.notNull();
			}
			if (column.unique()) {
				options.unique();
			}
			if (column.autoIncrement()) {
				options.autoIncrement();
			}
			column.defaultValue().ifPresent(options::defaultValue);
		});
	}
	
	/**
	 * Creates a table from its {@link Schema} definition: every column it declares, in declaration order.
	 *
	 * @param builder The migration under construction
	 * @param table The table to create
	 * @param primaryKey The primary key columns, in key order - a composite key for the tables that have
	 *   one, and the single column that declares {@code primaryKey()} for the rest
	 * @param <E> The row type of the table
	 */
	@SafeVarargs
	private static <E> void table(@NonNull SqlMigrationBuilder builder, @NonNull SqlTable<E> table, SqlColumn<E, ?> @NonNull ... primaryKey) {
		builder.createTable(table, definition -> {
			for (SqlColumn<E, ?> column : table.columns()) {
				column(definition, column);
			}
			definition.primaryKey(primaryKey);
		});
	}
	
	/**
	 * The initial schema (server-spec 5).
	 * <p>
	 * Type conventions follow the spec's mapping table: {@code UUID} ids (generated application-side,
	 * never by a database default), {@code TIMESTAMPTZ} timestamps stored UTC, {@code BYTEA} public keys,
	 * {@code BOOLEAN} flags, {@code DATE} daily dates computed in {@code SUDOKU_TIMEZONE} at write time -
	 * never derived from a timestamp at query time, or the rollover zone silently stops applying - and
	 * {@code BIGINT} identity columns for the two append-only tables.
	 */
	private static final class InitialSchema implements SqlMigration {
		
		@Override
		public @NonNull Version version() {
			// A literal, not CURRENT_VERSION: this migration's identity is fixed forever, while
			// CURRENT_VERSION moves with every one appended after it.
			return Version.of(1, 0);
		}
		
		@Override
		public @NonNull String description() {
			return "initial schema";
		}
		
		@Override
		public void up(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			this.tables(builder);
			this.indexes(builder);
		}
		
		private void tables(@NonNull SqlMigrationBuilder builder) {
			table(builder, SERVER_META, META_KEY);
			table(builder, USERS, USER_ID);
			table(builder, DEVICES, DEVICE_ID);
			table(builder, INVITES, INVITE_CODE);
			table(builder, LINK_CODES, LINK_CODE);
			table(builder, EMAIL_VERIFICATIONS, EMAIL_VERIFICATION_CODE);
			table(builder, RECOVERY_CODES, RECOVERY_CODE);
			table(builder, SESSIONS, SESSION_TOKEN);
			table(builder, AUTH_CHALLENGES, CHALLENGE_NONCE);
			table(builder, DAILY_RESULTS, RESULT_ID);
			table(builder, STREAKS, STREAK_USER_ID);
			table(builder, STATS, STATS_USER_ID, STATS_SIZE, STATS_VARIANT, STATS_DIFFICULTY);
			table(builder, DAILY_LEADERBOARD, LEADERBOARD_DATE, LEADERBOARD_DIFFICULTY, LEADERBOARD_USER_ID);
			table(builder, MATCHES, MATCH_ID);
			table(builder, MATCH_PARTICIPANTS, PARTICIPANT_MATCH_ID, PARTICIPANT_USER_ID);
			table(builder, CURRENCY_LEDGER, LEDGER_ID);
			table(builder, DAILY_PREFERENCES, PREFERENCE_USER_ID);
			table(builder, DAILY_ASSIGNMENTS, ASSIGNMENT_USER_ID, ASSIGNMENT_DATE);
		}
		
		/**
		 * Every index the server's own queries need. Primary and unique keys bring their own, so only the
		 * lookups that are neither are listed here.
		 */
		private void indexes(@NonNull SqlMigrationBuilder builder) {
			// Supports the last-admin invariant check (spec 7.1) without scanning the table.
			builder.createIndex(USERS, "users_active_admins_idx", index -> index.columns(USER_ROLE, USER_REVOKED));
			builder.createIndex(DEVICES, "devices_user_idx", index -> index.columns(DEVICE_USER_ID));
			builder.createIndex(LINK_CODES, "link_codes_user_idx", index -> index.columns(LINK_USER_ID));
			builder.createIndex(EMAIL_VERIFICATIONS, "email_verifications_user_idx", index -> index.columns(EMAIL_VERIFICATION_USER_ID));
			builder.createIndex(RECOVERY_CODES, "recovery_codes_user_idx", index -> index.columns(RECOVERY_USER_ID));
			// Sweeping expired challenges is a cheap range scan on this index.
			builder.createIndex(AUTH_CHALLENGES, "auth_challenges_expiry_idx", index -> index.columns(CHALLENGE_EXPIRES_AT));
			// Rows are folded into stats and pruned once the day has rolled over; this index drives both.
			builder.createIndex(DAILY_RESULTS, "daily_results_date_idx", index -> index.columns(RESULT_DATE));
			// Ranking within one tier; pruned at rollover after stats have been updated.
			builder.createIndex(DAILY_LEADERBOARD, "daily_leaderboard_rank_idx", index -> index.columns(LEADERBOARD_DATE, LEADERBOARD_DIFFICULTY, LEADERBOARD_ELAPSED_MS));
			// Crash recovery on startup scans for matches left RUNNING (spec 9a.3).
			builder.createIndex(MATCHES, "matches_state_idx", index -> index.columns(MATCH_STATE));
			builder.createIndex(MATCH_PARTICIPANTS, "match_participants_user_idx", index -> index.columns(PARTICIPANT_USER_ID));
			// Balance is derived as SUM(delta) and never stored as a mutable column (spec 5.1).
			builder.createIndex(CURRENCY_LEDGER, "currency_ledger_balance_idx", index -> index.columns(LEDGER_USER_ID, LEDGER_ID));
			builder.createIndex(CURRENCY_LEDGER, "currency_ledger_history_idx", index -> index.columns(LEDGER_USER_ID, LEDGER_CREATED_AT));
		}
		
		/**
		 * Drops everything, children before parents, so a rollback leaves an empty schema rather than a
		 * table whose foreign key points at something that is already gone.
		 */
		@Override
		public void down(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			List<SqlTable<?>> tables = List.of(
				DAILY_ASSIGNMENTS, DAILY_PREFERENCES, CURRENCY_LEDGER, MATCH_PARTICIPANTS, MATCHES, DAILY_LEADERBOARD,
				STATS, STREAKS, DAILY_RESULTS, AUTH_CHALLENGES, SESSIONS, RECOVERY_CODES, EMAIL_VERIFICATIONS,
				LINK_CODES, INVITES, DEVICES, USERS, SERVER_META
			);
			for (SqlTable<?> table : tables) {
				builder.dropTable(table);
			}
		}
	}
	
	/**
	 * Presence as a heartbeat table plus stored match requests, replacing the presence WebSocket.
	 * <p>
	 * Both tables exist because presence stopped being connection state. {@code presence} holds the last
	 * heartbeat per user and "online" is derived from its age, so a client that vanished without closing
	 * anything goes offline on its own; {@code match_requests} holds what the socket used to push, because
	 * there is no longer a connection to push it down.
	 */
	private static final class PresencePolling implements SqlMigration {
		
		@Override
		public @NonNull Version version() {
			return Version.of(2, 0);
		}
		
		@Override
		public @NonNull String description() {
			return "presence heartbeats and stored match requests";
		}
		
		@Override
		public void up(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			table(builder, PRESENCE, PRESENCE_USER_ID);
			table(builder, MATCH_REQUESTS, REQUEST_ID);
			
			// Read on every heartbeat - the one query on this table that is not by primary key.
			builder.createIndex(MATCH_REQUESTS, "match_requests_target_idx", index -> index.columns(REQUEST_TARGET_USER_ID));
			// Deliberately no index on presence.last_seen_at, the column every heartbeat rewrites: indexing it
			// would rule out Postgres' heap-only tuple updates on the server's most frequently written row, to
			// speed up a scan of at most one row per registered player. The primary key is the only index here.
		}
		
		@Override
		public void down(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			builder.dropTable(MATCH_REQUESTS);
			builder.dropTable(PRESENCE);
		}
	}
	
	/**
	 * Records which device revocations came from a kick, so a kick can be undone (spec 7.2).
	 * <p>
	 * A kick revokes every key the user has, which is what makes it stick. Reinstating them has to give
	 * those keys back - there is no other way for that account to authenticate again, and issuing a fresh
	 * invite would produce a <em>new</em> user with none of the old one's history. What it must not give
	 * back is a key its owner revoked deliberately before the kick, and once both are simply
	 * {@code revoked = true} the two are indistinguishable. Hence one column, written by the kick and
	 * cleared by the reinstatement.
	 * <p>
	 * The default of {@code false} is the right answer for every row that already exists: a device revoked
	 * before this migration is one nobody can prove was kicked, and leaving it dead is the safe direction.
	 * <p>
	 * <strong>Why the column is added conditionally.</strong> {@link #table} builds a table from whatever
	 * {@link Schema} declares <em>now</em>, so adding a column to {@code Schema} retroactively changes what
	 * the initial migration creates: a database created today already has {@code revoked_by_kick} by the
	 * time this migration runs, while one deployed before today does not. Both must end up at the same
	 * schema, so this asks. That is a property of deriving DDL from the live table definitions rather than
	 * a quirk of this column - <strong>every future column addition needs the same guard</strong>. The
	 * drift is invisible to the runner, which skips applied versions without re-rendering them; it would
	 * surface as a checksum mismatch only if {@code SqlMigrationRunner.validate()} were ever called, which
	 * {@code db/Migrations} deliberately does not do.
	 */
	/**
	 * Makes "may hints be spent" a property of the match rather than of each client.
	 * <p>
	 * The Android client had it as an in-game switch, which is wrong twice over on a shared board: the
	 * players in one co-op match could disagree about whether the match allowed hints, and nothing the
	 * creator configured said anything about it. It belongs with {@code lives_enabled}, decided once when
	 * the match is created and reported to every participant in {@code MATCH_STATE}.
	 * <p>
	 * The default is {@code true}, the opposite way round from {@code lives_enabled}. Every match that
	 * exists today was played by clients that offered hints with nothing to stop them, so {@code true} is
	 * what those rows actually were; defaulting to {@code false} would retroactively describe them as
	 * stricter matches than they were.
	 * <p>
	 * Guarded with {@code hasColumn} for the reason spelled out on {@link ReinstatableKicks}: {@link #table}
	 * renders the initial migration from {@link Schema} as it stands <em>now</em>, so a database created
	 * after this column was declared already has it by the time this migration runs, while one deployed
	 * before does not.
	 */
	private static final class MatchHintSetting implements SqlMigration {
		
		@Override
		public @NonNull Version version() {
			return Version.of(4, 0);
		}
		
		@Override
		public @NonNull String description() {
			return "make hints a per-match setting";
		}
		
		@Override
		public void up(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			if (!schema.hasColumn(MATCHES.name(), MATCH_HINTS_ENABLED.name())) {
				builder.addColumn(MATCH_HINTS_ENABLED, MATCH_HINTS_ENABLED.type(), options -> options.notNull().defaultValue(true));
			}
		}
		
		@Override
		public void down(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			if (schema.hasColumn(MATCHES.name(), MATCH_HINTS_ENABLED.name())) {
				builder.dropColumn(MATCH_HINTS_ENABLED);
			}
		}
	}
	
	private static final class ReinstatableKicks implements SqlMigration {
		
		@Override
		public @NonNull Version version() {
			return Version.of(3, 0);
		}
		
		@Override
		public @NonNull String description() {
			return "record kick-revoked devices so a kick can be reversed";
		}
		
		@Override
		public void up(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			if (!schema.hasColumn(DEVICES.name(), DEVICE_REVOKED_BY_KICK.name())) {
				builder.addColumn(DEVICE_REVOKED_BY_KICK, DEVICE_REVOKED_BY_KICK.type(), options -> options.notNull().defaultValue(false));
			}
		}
		
		@Override
		public void down(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			if (schema.hasColumn(DEVICES.name(), DEVICE_REVOKED_BY_KICK.name())) {
				builder.dropColumn(DEVICE_REVOKED_BY_KICK);
			}
		}
	}
	
	/**
	 * Remembers which finished games have already been folded into {@code stats}, so that uploading one
	 * can be retried (spec 9).
	 * <p>
	 * Single-player results used to reach the server exactly once per device, in the bulk
	 * {@code POST /stats/sync} at the offline-to-online transition, and never again - so every game played
	 * afterwards existed only on the device, and a player's statistics were frozen at whatever they were
	 * when the device linked. The fix is to upload each game as it finishes, with an offline queue behind
	 * it; what that queue needs and {@code stats} cannot give it is a way to be replayed safely, since
	 * every column there increments. Hence this table: see {@link Schema#RECORDED_GAMES}.
	 * <p>
	 * No {@code hasColumn}-style guard is needed here, unlike {@link ReinstatableKicks} and
	 * {@link MatchHintSetting}: that guard exists because {@link #table} renders the <em>initial</em>
	 * migration from {@link Schema} as it stands now, and a new column therefore appears retroactively in
	 * a freshly created database. A new table does not - {@link InitialSchema} creates the tables it names
	 * and no others, and this one is not among them.
	 */
	private static final class RecordedGames implements SqlMigration {
		
		@Override
		public @NonNull Version version() {
			return Version.of(5, 0);
		}
		
		@Override
		public @NonNull String description() {
			return "remember folded games so a stats upload can be retried";
		}
		
		@Override
		public void up(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			table(builder, RECORDED_GAMES, RECORDED_USER_ID, RECORDED_GAME_ID);
			
			// The rollover prunes this table by age, which is the one query on it that is not by primary key.
			builder.createIndex(RECORDED_GAMES, "recorded_games_recorded_at_idx", index -> index.columns(RECORDED_AT));
		}
		
		@Override
		public void down(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			builder.dropTable(RECORDED_GAMES);
		}
	}
	
	/**
	 * Stores a match's generated givens, so the grid is generated once and never again.
	 * <p>
	 * A match row carried only the seed, and everything that needed the board - {@code GET /matches/{id}},
	 * rehydration after a restart, a player reconnecting - regenerated from the key. Under the fifteen-band
	 * rater that is up to a second of CPU on a request thread per rehydration, for a grid this server had
	 * already produced when the match was created. The givens are 42 bytes at 9x9 and rebuild the same
	 * puzzle with a backtracking solve instead.
	 * <p>
	 * <strong>Nullable, and it stays nullable.</strong> Every match created before this deploy has no
	 * givens, and backfilling them would mean regenerating exactly what this column exists to avoid
	 * regenerating. {@code MatchService.liveFor} falls back to the key for those rows, which is what it
	 * always did.
	 * <p>
	 * Guarded with {@code hasColumn} in both directions for the reason spelled out on
	 * {@link MatchHintSetting}: {@link #table} renders the initial migration from {@link Schema} as it
	 * stands <em>now</em>, so a database created after this column was declared already has it by the time
	 * this migration runs, while one deployed before does not.
	 */
	private static final class MatchGivens implements SqlMigration {
		
		@Override
		public @NonNull Version version() {
			return Version.of(6, 0);
		}
		
		@Override
		public @NonNull String description() {
			return "store a match's generated givens so it is never regenerated";
		}
		
		@Override
		public void up(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			if (!schema.hasColumn(MATCHES.name(), MATCH_GIVENS.name())) {
				builder.addColumn(MATCH_GIVENS, MATCH_GIVENS.type(), _ -> {});
			}
		}
		
		@Override
		public void down(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			if (schema.hasColumn(MATCHES.name(), MATCH_GIVENS.name())) {
				builder.dropColumn(MATCH_GIVENS);
			}
		}
	}
	
	/**
	 * Rewrites every stored difficulty from the six-tier scale onto the fifteen-tier one.
	 * <p>
	 * <strong>The one part of the rework a code change could not do.</strong> The difficulty integer did not
	 * gain values when the scale went from six bands to fifteen, it changed meaning, and
	 * {@link net.luis.sudoku.compat.LegacyDifficulty} translates that meaning on the <em>wire</em> - at the
	 * edge, per request, for a client that still speaks the old scale. It is never applied to a row that is
	 * already in the database. So without this migration every stored number keeps its digits and silently
	 * changes what it names the moment this version starts: a player who chose the fourth tier of six, the
	 * hard one, wakes up on the fourth of fifteen, which is easy, and their setting still reads {@code 4} and
	 * looks untouched. A daily assignment locked in before the deploy is replayed after it as a different
	 * band. Every statistics and leaderboard row silently re-buckets, and a v1 client reading its own
	 * statistics back gets stored {@code 4} through {@code toLegacy} as {@code 2}, not the {@code 4} it wrote.
	 * <p>
	 * <strong>Every row present when this runs is a legacy row, by definition.</strong> Migrations complete
	 * before this version serves its first request, so there is no need - and no way - to tell old rows from
	 * new ones: there are no new ones yet. On a database created fresh this runs against empty tables and
	 * changes nothing, which is why it is safe as an unconditional rewrite rather than a guarded one.
	 * <p>
	 * <strong>Injective, so the primary keys hold.</strong> {@code stats} and {@code daily_leaderboard} carry
	 * the difficulty inside their primary key, and rewriting a key column is only safe if no two source
	 * values collide on one target. The anchors {@code 1, 4, 7, 10, 13} plus Lisa are six distinct tiers
	 * mapping to six distinct tiers, so no row can ever be rewritten onto another. The per-value statements
	 * run highest first for the reason given on
	 * {@link net.luis.sudoku.compat.LegacyDifficulty#legacyTiersHighestFirst()}.
	 * <p>
	 * <strong>{@link #down} is honest about being lossy.</strong> Going back maps each real tier to the
	 * nearest legacy anchor, which is exact for every value this migration wrote and approximate for
	 * anything a v2 client has chosen since. That is the best a six-value field can do and is stated here
	 * rather than discovered later.
	 */
	private static final class RescaledDifficulties implements SqlMigration {
		
		@Override
		public @NonNull Version version() {
			return Version.of(7, 0);
		}
		
		@Override
		public @NonNull String description() {
			return "rescale stored difficulties from six tiers to fifteen";
		}
		
		@Override
		public void up(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			for (int legacy : LegacyDifficulty.legacyTiersHighestFirst()) {
				int real = LegacyDifficulty.fromLegacy(legacy).index();
				if (real == legacy) {
					continue; // Tier 1 is tier 1 on both scales.
				}
				
				builder.data(DAILY_PREFERENCES, queries -> queries.update()
					.set(PREFERENCE_DIFFICULTY, real).where(Sql.equalTo(PREFERENCE_DIFFICULTY, legacy)).execute());
				builder.data(DAILY_ASSIGNMENTS, queries -> queries.update()
					.set(ASSIGNMENT_DIFFICULTY, real).where(Sql.equalTo(ASSIGNMENT_DIFFICULTY, legacy)).execute());
				builder.data(DAILY_RESULTS, queries -> queries.update()
					.set(RESULT_DIFFICULTY, real).where(Sql.equalTo(RESULT_DIFFICULTY, legacy)).execute());
				builder.data(STATS, queries -> queries.update()
					.set(STATS_DIFFICULTY, real).where(Sql.equalTo(STATS_DIFFICULTY, legacy)).execute());
				builder.data(DAILY_LEADERBOARD, queries -> queries.update()
					.set(LEADERBOARD_DIFFICULTY, real).where(Sql.equalTo(LEADERBOARD_DIFFICULTY, legacy)).execute());
				// Typed rather than integer, so the column's own mapper does the conversion either way.
				builder.data(MATCHES, queries -> queries.update()
					.set(MATCH_DIFFICULTY, Difficulty.ofIndex(real)).where(Sql.equalTo(MATCH_DIFFICULTY, Difficulty.ofIndex(legacy))).execute());
			}
		}
		
		@Override
		public void down(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			// Ascending here, and for the mirror of the reason up() descends: every value written is below
			// every value a later statement looks for, so no row is rewritten twice.
			for (Difficulty difficulty : Difficulty.values()) {
				int legacy = LegacyDifficulty.toLegacy(difficulty);
				int real = difficulty.index();
				if (real == legacy) {
					continue;
				}
				
				builder.data(DAILY_PREFERENCES, queries -> queries.update()
					.set(PREFERENCE_DIFFICULTY, legacy).where(Sql.equalTo(PREFERENCE_DIFFICULTY, real)).execute());
				builder.data(DAILY_ASSIGNMENTS, queries -> queries.update()
					.set(ASSIGNMENT_DIFFICULTY, legacy).where(Sql.equalTo(ASSIGNMENT_DIFFICULTY, real)).execute());
				builder.data(DAILY_RESULTS, queries -> queries.update()
					.set(RESULT_DIFFICULTY, legacy).where(Sql.equalTo(RESULT_DIFFICULTY, real)).execute());
				builder.data(MATCHES, queries -> queries.update()
					.set(MATCH_DIFFICULTY, Difficulty.ofIndex(legacy)).where(Sql.equalTo(MATCH_DIFFICULTY, difficulty)).execute());
			}
			// stats and daily_leaderboard are deliberately left alone: the difficulty is inside their
			// primary key and the reverse mapping is many to one, so rolling them back would collide two
			// real tiers onto one legacy row and lose a player's record rather than merely blur it.
		}
	}
	
	/**
	 * Gives the pre-generation pool a table, so it survives a restart and is shared between instances.
	 * <p>
	 * {@code PuzzleQueue} held its pool in a map, which made it per-process twice over: a deploy threw away
	 * every puzzle it had paid to generate, and a second container generated its own copy of the same work.
	 * That was tolerable while the queue only fed match creation; it feeds every single-player game start as
	 * well now, across fifteen bands where the dear ones cost seconds apiece, so the pool is worth keeping.
	 * See {@link Schema#PUZZLE_POOL} for why a row carries the givens and no solution.
	 * <p>
	 * <strong>The index is the whole design.</strong> The only query on this table is "give me the oldest
	 * unclaimed row of this bucket at the current generator version", run as
	 * {@code ORDER BY id LIMIT n FOR UPDATE SKIP LOCKED} - which is what makes two instances unable to hand
	 * the same row to two players. Leading with the four bucket columns and ending with {@code id} means
	 * that claim is an index range scan whose first match is the row it wants, rather than a sort of the
	 * whole bucket; and {@code SKIP LOCKED} then walks the index forward past whatever another instance
	 * already holds instead of blocking on it.
	 * <p>
	 * No {@code hasColumn}-style guard, for the reason given on {@link RecordedGames}: the guard exists
	 * because {@link #table} renders the <em>initial</em> migration from {@link Schema} as it stands now, so
	 * a new column appears retroactively in a freshly created database. A new table does not.
	 */
	private static final class PooledPuzzles implements SqlMigration {
		
		@Override
		public @NonNull Version version() {
			return Version.of(8, 0);
		}
		
		@Override
		public @NonNull String description() {
			return "persist the pre-generated puzzle pool";
		}
		
		@Override
		public void up(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			table(builder, PUZZLE_POOL, POOL_ID);
			
			builder.createIndex(PUZZLE_POOL, "puzzle_pool_claim_idx", index -> index.columns(POOL_SIZE, POOL_VARIANT, POOL_DIFFICULTY, POOL_GEN_VERSION, POOL_ID));
		}
		
		@Override
		public void down(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			builder.dropTable(PUZZLE_POOL);
		}
	}

	/**
	 * Moves the session's uniqueness from the user to the device, so a player can be signed in on more
	 * than one device at a time.
	 * <p>
	 * {@code sessions.user_id} was unique, which is what made "link this device" sign the other one out.
	 * On its own that was merely the documented trade-off; what made it a defect is that the displaced
	 * row was <em>deleted</em>, so the displaced client could not tell the difference between "somebody
	 * else signed in" and "your session expired" - it received a plain {@code UNAUTHORIZED} for both -
	 * and its {@code SessionGuard} healed the expiry case by silently re-authenticating with the device
	 * key. That new session displaced the other device, whose next heartbeat did the same thing back.
	 * Two devices therefore took the session from each other every few seconds, each losing its match
	 * sockets on every swap, for as long as both apps were open.
	 * <p>
	 * {@code superseded_token}/{@code superseded_at} keep the replaced token readable on the row that
	 * replaced it, which is what lets {@code SessionService.authenticate} answer {@code SESSION_SUPERSEDED}
	 * instead of {@code UNAUTHORIZED} for the one displacement that still happens: a device replacing its
	 * own session. They live on the replacing row rather than as a tombstone row of their own precisely
	 * because {@code device_id} is unique now - a device has exactly one row, whatever has happened to it.
	 * <p>
	 * <strong>Both constraint changes are guarded, and by the live schema rather than by {@code hasColumn}.</strong>
	 * The reason is the one spelled out on {@link ReinstatableKicks}: {@link #table} renders the initial
	 * migration from {@link Schema} as it stands <em>now</em>, so a database created after this version
	 * exists already has the constraints the right way round by the time this runs, while one deployed
	 * before does not. {@code SqlMigrationSchema} tracks single-column uniqueness per column, which is
	 * exactly the question being asked - there is no {@code IF EXISTS} on a constraint drop to fall back on.
	 * <p>
	 * The index is unconditional because nothing creates it anywhere else: {@code user_id}'s lookups
	 * (a kick, listing a user's sessions) rode on the unique index that is being dropped here.
	 */
	private static final class PerDeviceSessions implements SqlMigration {

		/** The names Postgres gives a column-level {@code UNIQUE}, so both paths end up with one shape. */
		private static final String USER_UNIQUE = "sessions_user_id_key";
		private static final String DEVICE_UNIQUE = "sessions_device_id_key";

		@Override
		public @NonNull Version version() {
			return Version.of(9, 0);
		}

		@Override
		public @NonNull String description() {
			return "one session per device instead of one per user";
		}

		@Override
		public void up(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			if (!schema.hasColumn(SESSIONS.name(), SESSION_SUPERSEDED_TOKEN.name())) {
				builder.addColumn(SESSION_SUPERSEDED_TOKEN, SESSION_SUPERSEDED_TOKEN.type());
			}
			if (!schema.hasColumn(SESSIONS.name(), SESSION_SUPERSEDED_AT.name())) {
				builder.addColumn(SESSION_SUPERSEDED_AT, SESSION_SUPERSEDED_AT.type());
			}

			// No de-duplication is needed before the device constraint goes on: user_id was unique until
			// now and a device belongs to exactly one user, so no user can have two rows to collide with.
			if (isUnique(schema, SESSION_USER_ID)) {
				builder.dropConstraint(SESSIONS, USER_UNIQUE);
			}
			if (!isUnique(schema, SESSION_DEVICE_ID)) {
				builder.addUniqueConstraint(SESSIONS, DEVICE_UNIQUE, SESSION_DEVICE_ID);
			}

			builder.createIndex(SESSIONS, "sessions_user_idx", index -> index.columns(SESSION_USER_ID));
			// The failure path of authentication reads this, and only ever by equality.
			builder.createIndex(SESSIONS, "sessions_superseded_token_idx", index -> index.columns(SESSION_SUPERSEDED_TOKEN));
		}

		/**
		 * Rolling back re-imposes one session per user, which two signed-in devices would violate - so
		 * every session is dropped rather than a guess made about which device keeps the account. Signing
		 * in again is a challenge/response the client does by itself.
		 */
		@Override
		public void down(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			builder.data(SESSIONS, queries -> queries.delete().execute());

			builder.dropIndex(SESSIONS, "sessions_superseded_token_idx");
			builder.dropIndex(SESSIONS, "sessions_user_idx");
			if (isUnique(schema, SESSION_DEVICE_ID)) {
				builder.dropConstraint(SESSIONS, DEVICE_UNIQUE);
			}
			if (!isUnique(schema, SESSION_USER_ID)) {
				builder.addUniqueConstraint(SESSIONS, USER_UNIQUE, SESSION_USER_ID);
			}

			if (schema.hasColumn(SESSIONS.name(), SESSION_SUPERSEDED_AT.name())) {
				builder.dropColumn(SESSION_SUPERSEDED_AT);
			}
			if (schema.hasColumn(SESSIONS.name(), SESSION_SUPERSEDED_TOKEN.name())) {
				builder.dropColumn(SESSION_SUPERSEDED_TOKEN);
			}
		}

		/**
		 * Whether the live schema already carries a single-column unique index on this column, which is
		 * how {@link SqlMigrationSchema} records both a column-level {@code UNIQUE} and a named constraint.
		 */
		private static boolean isUnique(@NonNull SqlMigrationSchema schema, @NonNull SqlColumn<?, ?> column) throws SqlException {
			if (!schema.hasColumn(SESSIONS.name(), column.name())) {
				return false;
			}
			return schema.column(SESSIONS.name(), column.name()).unique();
		}
	}
	
	/**
	 * Adds the learn area's progress table, so a second device sees the techniques an account has already
	 * mastered instead of an empty wiki.
	 * <p>
	 * A new table rather than columns on an existing one, and therefore no {@code hasColumn} guard: the
	 * initial migration creates the tables it names and no others, and this one is not among them, so it is
	 * absent from a freshly created database exactly as it is from an old one.
	 * <p>
	 * The only query on it that is not by primary key is "everything this player has", which is the sync,
	 * so that is the one index it gets.
	 */
	private static final class LearnProgressTable implements SqlMigration {
		
		@Override
		public @NonNull Version version() {
			return Version.of(10, 0);
		}
		
		@Override
		public @NonNull String description() {
			return "remember a player's learn area progress across their devices";
		}
		
		@Override
		public void up(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			table(builder, LEARN_PROGRESS, LEARN_USER_ID, LEARN_TECHNIQUE, LEARN_LEVEL, LEARN_SUB_LEVEL);
			
			builder.createIndex(LEARN_PROGRESS, "learn_progress_user_idx", index -> index.columns(LEARN_USER_ID));
		}
		
		@Override
		public void down(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
			builder.dropTable(LEARN_PROGRESS);
		}
	}
}
