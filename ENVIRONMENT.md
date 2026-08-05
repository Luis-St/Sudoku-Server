# Environment variables

The server is configured entirely through environment variables; there is deliberately no
configuration file. Everything is read once at startup by
`net.luis.sudoku.config.ServerConfig#fromEnvironment`, and nothing outside the `config` package touches
`System.getenv`.

Parsing is fail-fast: a missing required variable or an unusable value kills the process at boot with a
precise message, instead of failing later on the first request that needs the value.

The canonical list of keys lives in
[`EnvKeys.java`](src/main/java/net/luis/sudoku/config/EnvKeys.java), and
[`.env.example`](.env.example) is a ready to copy template. `.env` itself is gitignored and is what both
compose files load via `env_file`.

## Value formats

| Type | Accepted input |
| --- | --- |
| Integer | Decimal digits, surrounding whitespace is trimmed |
| Decimal | Java `double` syntax, for example `0.5` |
| Boolean | `true`, `yes`, `1`, `on` / `false`, `no`, `0`, `off` (case insensitive) |
| Enum | Constant name, case insensitive |

A variable set to an empty or blank string counts as **unset**, since an empty value in a compose file
is nearly always an unset secret rather than an intentional value. That means a required variable
present but blank still fails at boot, and an optional one falls back to its default.

## Core

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `SUDOKU_PORT` | no | `7000` | Listen port. Must be within 1 to 65535. |
| `SUDOKU_SERVER_NAME` | no | `Sudoku Server` | Display name shown to clients. |
| `SUDOKU_TIMEZONE` | no | `UTC` | IANA zone id (for example `Europe/Berlin`) driving daily rollover and streak evaluation. Anything `ZoneId.of` rejects fails at boot. |
| `SUDOKU_BOOTSTRAP_INVITE` | **yes** | | Invite code that grants the very first admin. Restarting with a fresh value is the break-glass path when no admin exists. |
| `SUDOKU_TRUST_PROXY` | no | `true` | Whether `X-Real-IP` and `X-Forwarded-For` may be believed. True is correct for the documented deployment, where a reverse proxy terminates TLS in front of the server. Set it false only if the server is directly reachable, because those headers are client controlled then. |

There is no TLS configuration. The server always speaks plain HTTP and a reverse proxy terminates TLS
in front of it.

## Database

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `SUDOKU_DB_URL` | **yes** | | JDBC URL, for example `jdbc:postgresql://db:5432/sudoku`. |
| `SUDOKU_DB_USER` | **yes** | | Database user. |
| `SUDOKU_DB_PASSWORD` | **yes** | | Database password. Supplied from a secret, never committed. |
| `SUDOKU_DB_POOL_SIZE` | no | `10` | Maximum pooled connections. Must be at least 1; about 10 is ample for a friends' server. |

When the server runs in Docker and Postgres runs on the host, `host.docker.internal` in the URL works
because `docker-compose.yaml` maps it through `extra_hosts: host-gateway`.

## Daily puzzle

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `SUDOKU_DAILY_SIZE` | no | `9` | Grid edge length of the daily puzzle. One of 4, 6, 9, 12, 16. |

The daily variant is fixed to `CLASSIC` in code (`ServerConfig.DAILY_VARIANT`) and is not configurable.

## Duel mode

Time-bank tuning; all bank values are in seconds. Every integer here must be at least 1.

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `SUDOKU_DUEL_INITIAL_BANK` | no | `90` | Seconds each participant starts with. Must not exceed `SUDOKU_DUEL_MAX_BANK`. |
| `SUDOKU_DUEL_GAIN_CORRECT` | no | `6` | Seconds credited to the controller for a correct entry. |
| `SUDOKU_DUEL_LOSS_INCORRECT` | no | `20` | Seconds debited from the controller for an incorrect entry. |
| `SUDOKU_DUEL_MAX_BANK` | no | `180` | Ceiling a bank may be topped up or clamped to. |
| `SUDOKU_DUEL_MIN_TURN` | no | `10` | Seconds a turn lasts at minimum before a handover may occur. |
| `SUDOKU_DUEL_REGEN_RATIO` | no | `0.5` | Idle seconds gained per elapsed second while not in control. Must be within 0.0 to 1.0. |
| `SUDOKU_DUEL_MAX_HANDOVERS` | no | `40` | Stalemate cap. Reaching it decides the match on correct-cell count. |

## Match reconnect

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `SUDOKU_MATCH_RECONNECT_GRACE` | no | `60` | Seconds a dropped participant may take to return before the match is abandoned. Must be at least 1. |
| `SUDOKU_MATCH_RECONNECT_LIMIT` | no | `3` | Reconnects allowed per participant per match. May be 0, which forbids reconnecting. |

## Presence

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `SUDOKU_PRESENCE_ONLINE_TTL` | no | `30` | Seconds a heartbeat keeps a player online. Must be at least 1. |
| `SUDOKU_PRESENCE_REQUEST_TTL` | no | `60` | Seconds an undelivered match request stays worth delivering. Must be at least 1. |

Clients heartbeat every 10 seconds, so the online TTL has to leave room for at least two beats to go
missing before a player is called offline. One beat of slack is not enough: a GC pause, a cell handover
or a single slow request would make a player who is sitting right there flicker offline, which is the
failure everyone notices. The cost of the extra slack is that a genuinely closed app keeps showing
online for up to this long, which nobody notices.

The request TTL is short on purpose, because a match request names a specific match that somebody is
waiting in.

## Currency

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `SUDOKU_CURRENCY_DAILY_GAME_CAP` | no | `10` | Currency-earning normal games per day. May be 0, which disables earning from normal games. The daily puzzle sits outside the cap. |

## Mail

SMTP settings for account recovery email. This block is all-or-nothing: if `SUDOKU_SMTP_HOST` is unset,
mail is simply not configured and the server cannot send email. Once a host is given, the credentials
and the sender address become required, because a half-configured mailer would fail on the first send
anyway.

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `SUDOKU_SMTP_HOST` | no | | SMTP server host. Setting it enables mail and makes the rest of this block required. |
| `SUDOKU_SMTP_PORT` | no | `587` | SMTP server port, 1 to 65535. The default is the default port of the chosen security mode. |
| `SUDOKU_SMTP_SECURITY` | no | `STARTTLS` | Transport security: `PLAINTEXT` (port 25), `IMPLICIT_TLS` (port 465) or `STARTTLS` (port 587). |
| `SUDOKU_SMTP_USERNAME` | if host set | | SMTP auth username. |
| `SUDOKU_SMTP_PASSWORD` | if host set | | SMTP auth password. |
| `SUDOKU_SMTP_FROM` | if host set | | The `From:` address on outgoing mail. |

Note that `SUDOKU_SMTP_PORT` is read independently of `SUDOKU_SMTP_SECURITY`. The mode's default port
only applies when the port is left unset, so a non-default combination such as `IMPLICIT_TLS` on port
587 is accepted as written.

## Minimal configuration

Four variables are required, everything else has a working default:

```dotenv
SUDOKU_DB_URL=jdbc:postgresql://db:5432/sudoku
SUDOKU_DB_USER=sudoku
SUDOKU_DB_PASSWORD=...
SUDOKU_BOOTSTRAP_INVITE=...
```
