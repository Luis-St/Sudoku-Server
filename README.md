# Sudoku-Server

Javalin + Postgres backend for the Sudoku project. Specification: [`../sudoku-server-spec.md`](../sudoku-server-spec.md).
Build plan and phase status: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).

Puzzle generation lives entirely in shared-core (`net.luis:sudoku-lib:1.0.0`), which the server and the
Android client both depend on at an **exact** version. The server issues a `PuzzleKey`; the grid itself
is never sent over the wire. See [`../SHARED_CORE_API.md`](../SHARED_CORE_API.md).

> shared-core has never been published to the Artifactory (`maven.luis-st.net`) - it's the owner's
> private lib - so it resolves from the developer's local Maven repository. Run
> `cd ../Sudoku-Lib && VERSION=1.0.0 ./gradlew publishToMavenLocal` if the dependency fails to resolve.

## Running locally

Start a Postgres and point the server at it:

```bash
docker run -d --name sudoku-pg -p 5432:5432 \
  -e POSTGRES_DB=sudoku -e POSTGRES_USER=sudoku -e POSTGRES_PASSWORD=devpassword \
  postgres:16-alpine

SUDOKU_DB_URL=jdbc:postgresql://127.0.0.1:5432/sudoku \
SUDOKU_DB_USER=sudoku \
SUDOKU_DB_PASSWORD=devpassword \
SUDOKU_BOOTSTRAP_INVITE=dev-bootstrap \
./gradlew run
```

Then:

```bash
curl localhost:7000/health
curl localhost:7000/api/v1/server-info
```

Swagger UI is at `http://localhost:7000/swagger`, and `openapi.json` is regenerated on every
`compileJava`.

Or with Compose, which brings its own Postgres:

```bash
./gradlew shadowJar
docker compose -f compose.yaml -f compose.local.yaml up --build
```

## Deployment

`compose.yaml` is the deployment file and defines **no database** — the target server already runs
Postgres. `compose.local.yaml` is a development override that adds one. Copy `.env.example` to `.env`
and fill in the secrets; `.env` is gitignored and must never be committed.

## TLS is nginx's job, not the server's

**The server speaks plain HTTP and never terminates TLS.** nginx sits in front and handles the
certificate, which is why the deployment file binds to `127.0.0.1:7000` — the app must never be
directly reachable.

Two things the proxy must do, or the server misbehaves in ways that are awkward to diagnose:

- **Forward the WebSocket upgrade on `/ws/`** (`proxy_http_version 1.1` plus the `Upgrade` and
  `Connection` headers). Without them the upgrade fails and multiplayer never connects at all. Give
  that location a generous read timeout too — a duel can sit quiet between moves, and the reconnect
  grace is 60s by default, so a short timeout cuts healthy matches.
- **Send `X-Real-IP` and `X-Forwarded-For`.** The server rate-limits `/auth/challenge`, `/auth/verify`,
  `/register` and `/devices/link` per client address (spec §12). Without these headers it sees only
  the proxy's address, and all four buckets collapse into a single counter shared by every client — one
  abusive client would lock out everyone. `X-Real-IP` is preferred because nginx *overwrites* it
  (`$remote_addr`), so a client cannot forge it; `X-Forwarded-For` is *appended* to
  (`$proxy_add_x_forwarded_for`), so the server reads its **last** entry rather than its first.

Set `SUDOKU_TRUST_PROXY=false` only if you deliberately expose the server directly, in which case those
headers are client-controlled and are ignored.

Use a real domain with an ACME certificate. A self-signed certificate is rejected by Android's default
trust store, and accepting one needs an explicit network security configuration in the app (spec §4).

### One-time Postgres provisioning

The deployment file creates no database, so the existing Postgres needs this once (spec §3.3):

```sql
CREATE DATABASE sudoku;
CREATE USER sudoku WITH PASSWORD '…';
GRANT ALL PRIVILEGES ON DATABASE sudoku TO sudoku;
```

On Postgres 15+, also grant schema rights, since `public` is no longer writable by default:

```sql
\c sudoku
GRANT ALL ON SCHEMA public TO sudoku;
```

No extensions are required — `gen_random_uuid()` is built into Postgres 13 and later. The server
applies its own migrations on startup, so nothing else is needed by hand.

## Configuration

All configuration is environment-based; there is no configuration file. The full table is in spec §3,
and every variable is listed with its default in [`.env.example`](.env.example). Parsing is fail-fast:
a missing required variable or an unusable value aborts startup with a message naming the variable.

## Database access

Connection pooling, transactions and dialect handling go through **LUtils**
(`net.luis.utils.io.database`), wrapped by `db/Database.java`. Services express intent - `transaction`,
`read`, `execute` - and never manage JDBC lifecycle themselves. `db/schema/Schema.java` declares every
table and column as typed `SqlTable`/`SqlColumn` definitions.

> LUtils resolves from the Artifactory (`maven.luis-st.net`) - the outage there only blocks *publishing*
> new projects, not fetching existing ones. Its published POM declares no dependencies, so guava,
> commons-lang3, jetbrains-annotations, HikariCP and the JDBC driver are declared explicitly in
> `build.gradle.kts`.

## Migrations

Forward-only numbered SQL under `src/main/resources/migrations`, tracked in
`server_meta['schema_version']` and applied at startup under a `pg_advisory_lock` so two restarting
containers cannot migrate concurrently. Each script runs in one transaction with its version bump.

To add one: write `V<n>__<name>.sql` and append a `Script` entry to `Migrations.SCRIPTS`. The list is
explicit rather than classpath-scanned, because scanning behaves differently inside the shadow jar.
**Never edit or renumber a released script.**

**Why DDL is plain SQL rather than LUtils' `SqlMigrationRunner`.** That runner snapshots the schema it
creates by reading it back through `SqlJdbcTypeMapper`. On Postgres, LUtils' own dialect renders `UUID`
columns as a native `uuid`, which pgjdbc reports as JDBC `OTHER` (1111) - a code the mapper has no case
for. It therefore throws while introspecting the schema it just built and rolls the migration back.
Every table here has a UUID column, so the runner is unusable until that is fixed in LUtils. This is a
narrow carve-out: DDL only. Everything else still goes through LUtils.

## `serverId` is load-bearing

Generated once on first startup as a random 128-bit value and stored in `server_meta`. It seeds every
daily puzzle, so if it changes, every daily changes and all historical daily results are orphaned.
**Back it up with the database** — a `pg_dump` covers it, since it lives in an ordinary table.

## Testing

```bash
./gradlew test
```
