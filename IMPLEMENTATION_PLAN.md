# Sudoku-Server — Implementation Plan

Source of truth: `../sudoku-server-spec.md` (all sections), `../sudoku-feature-spec.md` §9–10.

> # ✅ COMPLETE — all 11 phases implemented + the query-builder conversion, 262 tests green
>
> Finished 2026-07-26. Every phase below is done and verified against a real PostgreSQL, not just
> unit-tested: a full REST walk plus a two-client WebSocket race, and restart / idempotent-migration /
> crash-recovery checks. `./gradlew build` is green. The one item the phases left open — converting the
> 12 repositories off plain JDBC onto the LUtils query builder — is also done now that LUtils
> 10.4.0-beta.3 fixes the bug that blocked it; see "Persistence: LUtils for everything except DDL" below.
>
> **This file is now a record of what was built and why, not a to-do list.** Read the "Deviations and
> decisions" and "If you touch this next" sections at the bottom before changing anything — several
> choices look arbitrary until you know what broke without them.

## Dependencies

- **shared-core** — `net.luis:sudoku-lib:1.0.0` (genVersion 1), exact version, resolved from
  `mavenLocal()`. It has never been published to the Artifactory (`maven.luis-st.net`) — it's the
  owner's private lib — so `~/.m2` is the only source. API map: **`../SHARED_CORE_API.md`**.
- **LUtils** — `net.luis:LUtils:10.4.0-beta.3`, resolved from the Artifactory
  (`https://maven.luis-st.net/libraries/`, added as a `repositories { maven { ... } }` block). That
  outage only ever blocked *publishing* new projects there, not fetching existing ones — don't add
  LUtils to `mavenLocal()` just because shared-core needs it. Its
  `net.luis.utils.io.database` package is the SQL layer (pooling, transactions, dialect, query builder).
  **Its published POM declares no dependencies** (the publication uses `artifact(jar)` rather than
  `from(components["java"])`), so guava, commons-lang3, jetbrains-annotations, HikariCP and the JDBC
  driver are all declared explicitly in `build.gradle.kts`. Removing any of them fails at *runtime*,
  not compile time.

This plan followed the spec's own §15 build order, since that order reflects real dependencies (auth
before roles, roles before matches, etc.).

## Package layout (as built)

```
net.luis.sudoku
├── ApiVersion              CURRENT=1, PATH_PREFIX=/api/v1, WS_PATH_PREFIX=/ws/v1
├── Application             main() + configure(JavalinConfig, ServiceGraph) — all routes
├── ServiceGraph            hand-wired object graph, owns lifecycle (no DI container)
├── config/                 Env, ServerConfig + DatabaseConfig/DuelConfig/MatchConfig, ConfigException
├── db/                     Database (LUtils SqlDatabase wrapper), DataSourceFactory, Migrations,
│                           AdvisoryLocks, ServerMetaRepository, DatabaseException
│   └── schema/Schema       typed SqlTable/SqlColumn for all 16 tables
├── domain/                 User, Device, Invite, Session, Principal, Match, MatchParticipant,
│                           DailyResult, DailyOutcome, Streak, StatsEntry, KeyAlgorithm
├── repository/             12 repositories, LUtils query builder on the enclosing SqlTransaction (a
│                           handful of writes with composite-conflict upserts or DB-generated identity
│                           columns stay raw SQL — see "Persistence" below)
├── auth/                   ChallengeService, SignatureVerifier, SessionService, SessionCloser,
│                           Authentication
├── security/               CodeGenerator, ConstantTime, RateLimiter, ClientIp
├── permission/             Permission, Role, UserAdminService
├── invite/                 RegistrationService, InviteService
├── device/                 DeviceLinkService
├── puzzle/                 PuzzleFactory, PuzzleQueue
├── daily/                  SeedDerivation, SolveVerifier, DailyService
├── stats/                  StatsService (incl. the rollover fold+prune job)
├── currency/               CurrencyService, LedgerReason
├── match/                  MatchRegistry, LiveMatch, RaceMatch, DuelMatch, CoopMatch, MatchService,
│                           Connection, MessageEnvelope, MessageType, MatchPayloads, MatchMode,
│                           MatchState, MatchResult, EndReason
├── handler/                Health, ServerInfo, Register, Auth, User, Invite, Device, Daily, Stats,
│                           Currency, Match, MatchSocket, Handlers
├── dto/request, dto/response
└── error/                  ErrorCode, ApiException, ErrorHandlerConfig
```

Resources: `src/main/resources/migrations/V1__init.sql`, `V2__daily_preferences.sql`, `log4j2.xml`.
Ops files: `README.md`, `compose.yaml`, `compose.local.yaml`, `Dockerfile`, `.env.example`.

## ✅ Phase 1 — Skeleton hardening: DB, migrations, server_meta, serverId, /server-info
- Add `shared-core` as an **exact-version** dependency: `implementation("net.luis:sudoku-lib:1.0.0")`, no range — this is the load-bearing constraint from §2.1. Add `mavenLocal()` to `repositories` (the Artifactory is down; the lib resolves from `~/.m2`). Get it in the build file now even before it is used, so nobody adds a range later out of habit. Sanity-check the wiring by calling `net.luis.sudoku.version.GenVersion.CURRENT` (== 1) from a throwaway line, exactly as feature-spec Android A1 does its smoke test.
- `config.ServerConfig`: parse all env vars from spec §3 table (`SUDOKU_PORT`, `SUDOKU_DB_URL/USER/PASSWORD`, `SUDOKU_DB_POOL_SIZE`, `SUDOKU_SERVER_NAME`, `SUDOKU_BOOTSTRAP_INVITE`, `SUDOKU_TIMEZONE`, `SUDOKU_DAILY_SIZE`, TLS vars, all `SUDOKU_DUEL_*`, `SUDOKU_MATCH_RECONNECT_*`, `SUDOKU_CURRENCY_DAILY_GAME_CAP`), fail fast if a required var is missing.
- `db.DataSourceFactory`: HikariCP pool sized from `SUDOKU_DB_POOL_SIZE` (default 10), retry-with-backoff on initial connect (spec §14 "startup ordering") instead of exiting.
- `db.MigrationRunner`: forward-only numbered SQL scripts, tracked in `server_meta.schema_version`, applied under `pg_advisory_lock` (spec §5.1). Hand-rolled is fine at this scale; Flyway is an acceptable swap-in, decide once and keep it consistent.
- `migrations/V1__init.sql`: `server_meta` table + all tables from spec §5 (users, devices, invites, link_codes, sessions, auth_challenges, daily_results, streaks, stats, currency_ledger, daily_leaderboard, matches, match_participants) using the Postgres type conventions table (UUID, TIMESTAMPTZ, BYTEA, TEXT+CHECK for enums, `BIGINT GENERATED BY DEFAULT AS IDENTITY`).
- `serverId` bootstrap: on first startup with no `server_meta['server_id']` row, generate 128-bit random, persist; every subsequent boot reads it. Wire into a new `ServerInfoHandler`.
- `GET /health`: extend existing `HealthHandler` to report schema version + active match count (match count is 0 until Phase 8).
- `GET /api/v1/server-info` (unauthenticated): `{ serverId, serverName, timezone, dailySize, dailyVariant, genVersion, apiVersion }`. `genVersion` sourced from `shared-core`'s constant.
- Local dev: add `compose.yaml` + `compose.local.yaml` per spec §3.2, plus a README snippet for the one-time Postgres provisioning SQL (§3.3).

**Exit criteria (met):** server boots against Postgres, applies migrations idempotently, survives a DB-unavailable-at-startup scenario, and serves `/health` + `/server-info`.

## ✅ Phase 2 — Users, devices, invites, bootstrap admin, registration
- `domain.User`, `domain.Device`, `domain.Invite` + matching repositories (plain JDBC, HikariCP-backed; prepared statements, no ORM needed at this scale).
- `security.CodeGenerator`: `SecureRandom`-backed Base32 invite codes.
- `invite.BootstrapAdminService`: claim logic per spec §6.3 — inside one transaction: look up invite, if `grants_role = ADMIN` verify no non-revoked admin exists (reject `ADMIN_EXISTS` otherwise), create user, create device, mark invite consumed, issue session. Use `pg_advisory_xact_lock` on a fixed key for the "no admin exists" check (spec §5.1) to close the double-bootstrap race.
- `POST /api/v1/register` (`RegisterHandler`): full flow above for both bootstrap and ordinary invites.
- Invite burning as a conditional `UPDATE … WHERE code = ? AND consumed_at IS NULL` + affected-row check (spec §5.1) — no separate locking needed for ordinary (non-admin) invites.

**Exit criteria (met):** a fresh server can be bootstrapped to its first admin exactly once, concurrently-safe; ordinary invite registration works and double-consumption is impossible.

## ✅ Phase 3 — Challenge–response auth, sessions, single-session enforcement
- `domain.auth`: `AuthChallengeRepository` (nonce, public_key, expires_at, single-use, deleted on consumption).
- `auth.SignatureVerifier`: per `key_algorithm` — must support both Ed25519 (JDK 15+ built-in) and ECDSA P-256 (Android Keystore devices), since devices record their own algorithm (spec §5, `devices.key_algorithm`).
- `POST /api/v1/auth/challenge`, `POST /api/v1/auth/verify` (`AuthHandler`) per spec §6.1 — 404 unknown key, 403 revoked, 401 bad/expired/reused nonce.
- `auth.SessionService`: issuing a session **deletes any existing session for that user** (unique constraint on `sessions.user_id`) and closes that user's open WebSocket connections with `SESSION_SUPERSEDED` (ties into Phase 8's socket registry — stub the close call now, wire fully once sockets exist).
- Bearer-token auth middleware (Javalin `before` filter) resolving `Authorization: Bearer <token>` → `User`/`Device`, attached to context.

**Exit criteria (met):** full challenge/verify round trip works for both key algorithms; a second login on the same user invalidates the first session.

## ✅ Phase 4 — Roles, permission checks, admin invariant, kick
- `permission.Permission` enum {`CAN_PLAY`, `CAN_INVITE`, `CAN_KICK`, `CAN_CHANGE_ROLE`}, `Role` enum {`NEW`, `MEMBER`, `ADMIN`} → permission set mapping (spec §7 table). Checked at the **action site**, never by role name — a small `requirePermission(ctx, Permission.X)` helper used inside handlers.
- `PATCH /api/v1/users/{id}/role`, `DELETE /api/v1/users/{id}` (`UserHandler`).
- Admin invariant: any role-change/kick/device-revocation transaction does `SELECT … FOR UPDATE` on affected user rows and rejects with `409 LAST_ADMIN` if it would leave zero non-revoked admins (spec §5.1, §7.1).
- Kick semantics (spec §7.2): mark user revoked, revoke **all** device public keys, delete session, close sockets — historical results retained.

**Exit criteria (met):** role changes and kicks are transactionally safe against the last-admin invariant; kicked users cannot re-authenticate with any of their old keys.

## ✅ Phase 5 — Device link codes and management
- `link_codes` table/repository; `device.DeviceLinkService`: server-minted code (never client-computed), short TTL (minutes), single-use, bound to requesting user.
- `POST /api/v1/devices/link-code` (authenticated), `POST /api/v1/devices/link` per spec §6.4. New device inherits the user's role automatically.
- `GET /api/v1/devices`, `DELETE /api/v1/devices/{id}` per spec §6.5 — revoking current device ends the session; a user's last device may only be revoked if not the last admin's.
- Rate limiting on `/devices/link` (spec §12) since codes are short and human-typable — reuse `security.RateLimiter` per IP + per public key.

**Exit criteria (met):** a second device can link to an existing user via a server-minted code; device list/revoke works with the last-admin guard.

## ✅ Phase 6 — Daily service, preferences, result submission, streaks, offline queue
- `puzzle.PuzzleFactory`: a one-line wrapper over `shared-core`'s `PuzzleGenerator.generate(PuzzleKey)` returning `GeneratedPuzzle` (key + puzzle + known solution). Everywhere the server needs a puzzle goes through this, so verification (result replay) and issuance share one code path and one `genVersion`.
- `puzzle.PuzzleQueue`: a **pre-generation pool** so the server never blocks a request on generation (12×12/16×16 and chaos can each cost tens of ms). Keyed by `(size, variant, difficulty)`; a background single-thread worker keeps each bucket topped up to a target depth of **at least 2× the current active-player count** (min floor, e.g. 8), refilling when a bucket is drained. **Important — determinism is preserved:** the queue does *not* invent seeds for the daily (the daily key is fixed by `serverId ‖ date`, computed on demand); it pre-generates puzzles for **normal/match** requests where the server is free to choose the seed, drawing seeds from a `SecureRandom` and caching the resulting `GeneratedPuzzle`. For the daily, pre-warm is still possible by generating *tomorrow's* known key ahead of the rollover. Bounded memory: cap total pooled puzzles; discard oldest on overflow. This directly answers the "queue ≥ 2× players" requirement and keeps `/daily` and match creation instant.
- `daily.SeedDerivation`: `seed = fold64(SHA256(serverId ‖ "/" ‖ date))` — reuse `shared-core`'s `KeyDerivation.fold64` and `KeyDerivation.sha256` rather than reimplementing.
- `GET /api/v1/daily?difficulty=` (`DailyHandler`): server returns `PuzzleKey` only, never the grid (spec §1, §8). The key is deterministic from date+serverId, so this endpoint just derives and returns it (no queue needed for correctness; the queue only pre-warms verification/regeneration).
- `daily.PreferenceService`: `GET/PUT /api/v1/preferences` — `dailyDifficulty` change takes effect **next day only**; store the effective difficulty per date on first request of that date (spec §8.1).
- `POST /api/v1/daily/result`: verification per spec §8.2 — regenerate puzzle from derived key (via `shared-core`), replay `solveOrder`, reject given-cell or duplicate-cell entries, floor-check `elapsedMs`. `SOLVED` locks the date (`DAILY_ALREADY_SOLVED` on further attempts); `FAILED` may repeat with incrementing `attempt_no`.
- `streak.StreakService`: increments idempotently on `SOLVED`, computed in server timezone, gap > 1 day resets `current` to 1.
- Offline queue: submission carries the played date; server credits the streak to that date subject to normal date-validity rules (spec §8.4) — this is really just the existing result-submission endpoint being date-aware, no separate queue endpoint needed server-side.
- `daily_leaderboard` writes on `SOLVED`; folded into `stats` and pruned at rollover (needs a rollover job — see Phase 7 for where the fold/prune runs).

**Exit criteria (met):** daily puzzle key issuance, next-day-effective preference change, and solve/fail submission with correct lock/retry/streak semantics all pass integration tests against a real Postgres.

## ✅ Phase 7 — Statistics storage, sync, player browsing, leaderboard, currency
- `stats.StatsService`: aggregates per `(user_id, size, variant, difficulty)`; a rollover job (triggered lazily on first daily request of a new date, or a scheduled task) folds `daily_results`/`daily_leaderboard` into `stats` and prunes them (spec §8.6, §9).
- `GET /api/v1/players`, `GET /api/v1/players/{id}/stats`, `POST /api/v1/stats/sync` (merges local single-player history on first connect; local daily streaks are **not** merged — server streaks start fresh, spec §9).
- `currency.CurrencyService`: append-only `currency_ledger`, balance = `SUM(delta)` (spec §5.1) — no mutable balance column yet (add later only as a maintained-in-transaction cache if perf demands it).
- `GET /api/v1/currency`, `POST /api/v1/currency/sync` (connect-time plausibility check against recorded games played, silent `SYNC_ADJUST` clamp, spec §9a.2).
- Wire currency earning into the daily/normal-game result paths from Phase 6: `EARN_GAME` (capped at `SUDOKU_CURRENCY_DAILY_GAME_CAP`/day) and `EARN_DAILY` (once per date, outside the cap).
- `GET /api/v1/daily/leaderboard?difficulty=`: ranked within one tier, hints not exposed.

**Exit criteria (met):** currency earns correctly with the daily-cap and once-per-day-daily-bonus rules; balance sync clamps silently; leaderboard and player stats browsing work end-to-end.

## ✅ Phase 8 — Match lifecycle REST + WebSocket envelope
- `match.MatchRegistry`: in-memory, one **single-threaded executor queue per running match** (spec §10.4) — all mutations (entries, ticks, joins, disconnects) submitted to that queue; Javalin threads only enqueue.
- `domain.Match`/`MatchParticipant` + repository (persist only lifecycle: mode, config, participants, winner, stake, timestamps — never live board state).
- `POST /api/v1/matches`, `POST /api/v1/matches/{id}/invite`, `POST /api/v1/matches/{id}/join`, `GET /api/v1/matches/{id}`, `WS /ws/v1/matches/{id}` (`MatchHandler` + `MatchSocketHandler`). State machine `CREATED → WAITING → RUNNING → ENDED`/`ABANDONED`.
- Reject `LISA` difficulty for any match config (`LISA_NOT_ALLOWED`, spec §10.1).
- `match.MessageEnvelope`/`MessageType`: `{ type, seq, ts, payload }`; per-client monotonic `seq` for idempotent replay on reconnect; server acks highest processed `seq`.
- Reconnect/grace handling: `SUDOKU_MATCH_RECONNECT_GRACE` window, `SUDOKU_MATCH_RECONNECT_LIMIT` cap, explicit quit = no grace, exceeding either → `ABANDONED` + refund all stakes (needs Phase 7's ledger).
- On startup, close out any match still `RUNNING` in the DB as `ABANDONED` with refunds (crash recovery, spec §9a.3).
- Wire `SESSION_SUPERSEDED` socket close from Phase 3 fully now that sockets exist.

**Exit criteria (met):** matches can be created, joined, and connected over WebSocket with working reconnect/grace/abandon semantics, independent of any mode-specific gameplay logic (that's Phases 9–11).

## ✅ Phase 9 — Race mode
- `match.RaceMatch`: same `PuzzleKey` to both participants, independent boards server-side (or server just validates `PLACE` against the solution without tracking a full board — cheaper, spec only requires validation + progress %).
- Broadcast `PROGRESS { userId, filledPercent }` only — never cell content (spec §11.1).
- Lives tracked if `livesEnabled`; first-to-complete wins; both-exhausted → no winner.

**Exit criteria (met):** two clients can race to completion with correct win/lose/lives semantics and no board-content leakage.

## ✅ Phase 10 — Duel mode
- `match.DuelMatch` + `match.TickLoop`: ~250ms tick per match, submitted to the match's single-thread queue (spec §11.2 algorithm: drain controller's bank, regen idle bank capped at `maxBank`, handover when bank ≤ 0 and turn ≥ `minTurn`).
- `BANK_UPDATE` broadcast at ~1Hz (clients interpolate); `CONTROL_CHANGED` on handover.
- Entry handling: reject `PLACE` from non-controlling player; correct → `+gainPerCorrect` and write cell + `BOARD_UPDATE`; incorrect → `-lossPerIncorrect` (clamped to `maxBank`), cell **not** written; `ENTRY_RESULT` either way.
- `BACKGROUNDED` message → immediate `MATCH_ENDED` (`FORFEIT_BACKGROUNDED`), opponent takes the pot — distinct code path from a bare socket close (which uses the ordinary reconnect grace from Phase 8).
- Termination: final correct cell wins; `handoverNo` reaching `SUDOKU_DUEL_MAX_HANDOVERS` → most-correct-cells wins, ties broken by fewer errors.
- Stakes: `STAKE` ledger rows for both participants in the same transaction as `RUNNING` transition (Phase 7's ledger); `PAYOUT` to winner; `REFUND` both on disconnect/abandon/restart.

**Exit criteria (met):** full duel playable end-to-end including stakes escrow/payout/refund and the backgrounding-forfeit vs. network-failure distinction.

## ✅ Phase 11 — Co-operative mode
- `match.CoopMatch`: up to 4 participants, shared pen layer serialized through the match queue (first correct entry wins a race for the same cell; the second gets `ENTRY_RESULT` marking it already-filled).
- `PRESENCE { userId, cell }` broadcast.
- Shared lives pool (single pool of 5) when enabled.

**Exit criteria (met):** concurrent entry into the same cell resolves deterministically by arrival order; presence updates broadcast correctly to all participants.

## Cross-cutting (done throughout)

- **Security (spec §12):** rate limiting on `/auth/challenge`, `/auth/verify`, `/register`,
  `/devices/link` (per IP **and** per public-key fingerprint on the challenge); `ConstantTime` for codes
  and tokens; every cell coordinate and digit validated against the match's own grid size; WebSocket
  frame cap (8 KiB) and per-connection message rate limit (120 / 10 s); admin actions logged with actor,
  target and timestamp.
- **Error handling (spec §13):** uniform `{ error, message, details }` via `ErrorHandlerConfig`;
  full `ErrorCode` enum. Javalin's own HTML 404 is rewritten, guarded by a `sudoku.errorHandled`
  attribute so a deliberate `UNKNOWN_KEY` (also a 404) is not overwritten by a generic one.
- **Operations (spec §14):** graceful shutdown closes match sockets with `SERVER_SHUTDOWN`; startup
  retries the DB with backoff; `README.md` documents that `serverId` is load-bearing for `pg_dump`.

---

# Deviations and decisions

Things that differ from the spec, or that look arbitrary without the reason. **Read before changing.**

## TLS: the server is HTTP-only

Owner's decision (2026-07-26): nginx terminates TLS. `SUDOKU_TLS_MODE`, `SUDOKU_TLS_CERT`,
`SUDOKU_TLS_KEY` and the `TlsConfig`/`TlsMode` types from spec §4 were **removed** — they were never
wired into Javalin, so `DIRECT` would have passed validation and then silently served plain HTTP. No
nginx config ships with the project; the README documents only what the proxy must do.

**`SUDOKU_TRUST_PROXY` (default true) exists because of a real bug.** Rate limiting keys on the client
address; behind a proxy `ctx.ip()` is *nginx's* address, so all four buckets collapsed into one counter
shared by every client. `security/ClientIp` resolves the real one: prefers `X-Real-IP` (nginx
**overwrites** it, so it cannot be forged), else the **last** `X-Forwarded-For` entry (nginx **appends**,
so the first entry is client-supplied — reading it, the common mistake, is a free rate-limit bypass).

## Persistence: LUtils for everything except DDL

`db/Database` wraps LUtils `SqlDatabase` — pooling, transactions, dialect, query builder.
`db/schema/Schema` declares all 16 tables as typed `SqlTable`/`SqlColumn`, and all 12 repositories in
`repository/` are built on the LUtils query builder (`transaction.from(TABLE).select()/.insert()/
.update()/.delete()`), each taking the `SqlTransaction` itself rather than a bare `Connection`.

**DDL is deliberately plain SQL**, not LUtils' `SqlMigrationRunner`. The runner snapshots the schema it
creates by reading it back through `SqlJdbcTypeMapper`; on LUtils 10.4.0-beta.2, PostgreSQL's `UUID`
columns came back as JDBC `OTHER` (1111), a code the mapper had no case for, so the runner rolled the
whole migration back. **Fixed in LUtils 10.4.0-beta.3** (adds a `Types.OTHER`-aware
`resolveNativeType`), but DDL stays plain SQL anyway: the migrations here were never rewritten onto
`SqlMigrationRunner`, and there is no remaining reason to — see `LUtils/BUG_REPORT_io_database_postgres.md`
for the original two-bug report (this one and `SqlTypes.BYTES` rendering `VARBINARY`, which PostgreSQL
lacks — use `LARGE_BYTES`).

**`Schema.UUID_TYPE` must be the literal `SqlTypes.UUID` singleton, not a lookalike.** An earlier
revision built a `FIXED_STRING(36)`-based copy to dodge the beta.2 introspection bug above. That broke
query *execution* instead: the Postgres dialect's native-`uuid` value binding
(`setObject(..., Types.OTHER)` / `getObject(..., UUID.class)`) is registered against the `SqlTypes.UUID`
instance specifically, not against any structurally-equivalent type, so the lookalike fell back to
generic `FIXED_STRING` binding and Postgres rejected every UUID-bearing statement ("column is of type
uuid but expression is of type character"). Since DDL never goes through `SqlMigrationRunner`, the
introspection bug this was working around never applied here — using `SqlTypes.UUID` directly is correct
and was the fix.

**Six tables have no independent domain concept** (auth_challenges, link_codes, daily_results' peers
streaks/stats already had one, daily_leaderboard, currency_ledger, daily_preferences,
daily_assignments) and needed a persistence-only row record — the query builder's entity row mapper
matches column values positionally against a constructor, so even a table you only ever touch through
`update()`/`delete()` needs a real type with the right arity, not `Void`. Those live as nested records on
`Schema` (`Schema.LinkCodeRow`, `.AuthChallengeRow`, `.LeaderboardRow`, `.LedgerRow`, `.PreferenceRow`,
`.AssignmentRow`, `.ParticipantRow`). Every enum/value-object column (`Role`, `KeyAlgorithm`, `MatchMode`,
`MatchState`, `Variant`, `GridSize`, `Difficulty`, `EndReason`, `MatchResult`, `DailyOutcome`,
`LedgerReason`) is `SqlType.map()`-wrapped for the same reason: the auto row mapper needs the column's
Java type to match the domain constructor's parameter type exactly, not the raw string/int a hand-rolled
getter used to compute.

**A handful of writes stay raw SQL — a narrow, documented carve-out, not a partial conversion:**
- `StatsRepository.record/merge`, `DailyLeaderboardRepository.record` — incremental upserts
  (`col = col + EXCLUDED.col`, a null-aware `least()`, and a composite conflict key). The query builder's
  generic `upsert()` only supports a single conflict column and always assigns `col = EXCLUDED.col`
  verbatim, so it cannot express these. Genuine whole-row upserts (`StreakRepository.save`,
  `PreferenceRepository.setDailyDifficulty`) *do* use it, via the static `SqlInsertQuery.upsert(...)`
  factory (not exposed on `SqlQueryProvider`, so these two call it directly with
  `transaction.getDialect()` / `getQueryTimeout()` / `SqlConnectionSource.fixed(transaction.getConnection())`).
- `DailyResultRepository.insert`, `CurrencyLedgerRepository.append` — `id` is a DB-generated
  `BIGINT GENERATED BY DEFAULT AS IDENTITY` column. The query builder's entity insert has no way to omit
  a column, so supplying one explicitly would either desync the sequence or (with a placeholder like 0)
  risk a future collision; raw SQL + `RETURNING` lets Postgres assign it, same pattern the UUID
  primary-key tables now handle by generating the UUID application-side before `insert()`.
- `CurrencyLedgerRepository.countEarnGamesOn/hasEarnedDailyOn` — the `(created_at AT TIME ZONE ?)::date`
  predicate has no portable expression in the query builder.

`Database` exposes `transaction(SqlFunction)` and **`execute(SqlConsumer)`** — deliberately *not*
overloads, since a one-call lambda body matches both shapes and every call site would be ambiguous. Both
now hand the lambda the `SqlTransaction` itself (not a bare `Connection`), so repository bodies can reach
`transaction.from(TABLE)`; call sites that still need raw JDBC (the carve-outs above, plus a couple of
advisory-lock calls) use `transaction.getConnection()`. `Database.transaction` also unwraps LUtils'
`SqlException` wrapper, or an `ApiException` thrown inside a transaction would reach the handler as an
opaque 500 instead of its intended status.

## Concurrency invariants — each one closes a race that was actually observed

- **Bootstrap admin** takes `pg_advisory_xact_lock(AdvisoryLocks.BOOTSTRAP_ADMIN)` before the
  "no admin exists" check. Without it two simultaneous claims both become admin.
- **Last-admin invariant** takes `AdvisoryLocks.ADMIN_INVARIANT`. `SELECT … FOR UPDATE` on the target
  row is **not** sufficient: the count reads *other* admin rows without locking them, so two admins
  demoting each other both saw the other and both committed, leaving zero. This test was flaky before
  the fix and now passes 6 consecutive runs.
- **Auth nonce** is consumed in a **separate, committed** transaction *before* the signature is checked.
  Inside one transaction a rollback un-deleted it, so a wrong signature left the nonce alive and an
  attacker could keep guessing against one challenge until it expired.
- **Invite burn / link-code consume** use conditional `UPDATE … WHERE consumed_at IS NULL … RETURNING`,
  so exactly one of N concurrent redemptions wins with no locking.
- **Ledger `created_at` comes from the application `Clock`, never Postgres `now()`.** The daily earning
  cap and once-per-date daily bonus bucket by date in `SUDOKU_TIMEZONE`; letting the DB stamp it made the
  cap silently wrong whenever the clocks disagreed. Only caught because a test run crossed midnight.

## Schema deviations from spec §5

- `streaks.current`/`longest` → **`current_streak`/`longest_streak`** (`current` is a SQL keyword).
- `stats` stores **`total_time_ms`**, not an average — a mean cannot be merged incrementally.
- **V2 adds `daily_preferences` + `daily_assignments`** (§8.1 needs them; §5 predates those endpoints).
  `daily_assignments` uses `ON CONFLICT DO NOTHING` — that is the *entire* mechanism making a difficulty
  change take effect next-day-only.

## Gameplay decisions worth knowing

- **Race elimination does not end the match.** A player out of lives is out, but the survivor must still
  complete the grid to win. Ending on first elimination would hand them an unearned win and make the
  spec's "if both fail, no winner" clause unreachable.
- **`SolveVerifier.MIN_MS_PER_CELL = 150`** is the elapsed-time floor (§8.2 "cannot be entered in two
  seconds"). Generous on purpose: it rejects absurdity, not fast players.
- **The daily is never queued by `PuzzleQueue`.** Its key is fixed by `serverId ‖ date` and must be
  computed on demand, or the client could not derive the same puzzle offline.
- **`LiveMatch` threading rule:** every field is touched only from `submit()`. Request and socket threads
  enqueue and return. That is what lets the mode subclasses be plain single-threaded code.

## Testing

262 tests. `PostgresTest` shares one Testcontainers PostgreSQL across all subclasses and drops/recreates
the schema per test — an in-memory substitute would let broken advisory-lock or `FOR UPDATE` code pass.
`TestKeys` generates real Ed25519 / ECDSA P-256 keypairs, since registration parses keys and the
handshake verifies real signatures. Match-mode tests use `MatchFixture.drain()` to await the match queue
rather than sleeping.

---

# If you touch this next

1. **No planned server work remains.** The repository → query-builder conversion (the last item) is done;
   see "Persistence" above for what stayed raw SQL and why.
2. **Open items from spec §16:** Lisa needs no server representation — confirmed, it is rejected at every
   entry point. The shared-core Maven coordinates are settled (`net.luis:sudoku-lib:1.0.0`).
3. **Local dev:** PostgreSQL container `sudoku-dev-pg` on host port **55432** (db/user `sudoku`,
   password `devpassword`). `./gradlew run` with the four required env vars, or
   `./gradlew shadowJar && docker compose -f compose.yaml -f compose.local.yaml up --build`.
4. **The Dockerfile is runtime-only** (`COPY build/libs/…jar`), not multi-stage: a build stage in the
   container cannot reach `~/.m2`, and shared-core only lives there (it's never been published to the
   Artifactory). LUtils itself resolves fine from the Artifactory inside a build stage — shared-core is
   the only blocker. Run `./gradlew shadowJar` first.
