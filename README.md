# Bitbucket Helper

Bitbucket Helper is a local Kotlin/JVM service and product CLI for monitoring
authored Bitbucket pull requests, surfacing actionable activity, acknowledging
exact activity versions, and sending generic desktop notifications.

## Verified V1 surface

The V1 service composes the application core, SQLite persistence, the generated
Bitbucket client, Quartz schedules, loopback and Unix-socket HTTP transports, the
product CLI, and the `desktop-notifications` process adapter. The committed
[OpenAPI contract](openapi/api-v1.yaml) is the wire source of truth; a concise
consumer guide is in [docs/contracts/api-v1.md](docs/contracts/api-v1.md).

The product commands are:

```text
bitbucket-helper pr list
bitbucket-helper pr show <pull-request-id>
bitbucket-helper inbox
bitbucket-helper open <pull-request-id>
bitbucket-helper ack <action-item-id> <activity-version>
bitbucket-helper refresh [--repository <repository-id>]... [--no-wait]
bitbucket-helper workspace show
bitbucket-helper workspace configure --api-base-url <url> --slug <slug>
bitbucket-helper repository add <slug>
bitbucket-helper repository remove <repository-id>
bitbucket-helper service run
```

Product commands are Unix-socket clients only. They never fall back to loopback
TCP, SQLite, Bitbucket, or an in-process use case. Put `--output human|json` on
the executable product command, or on the `pr`, `workspace`, or `repository`
group before its subcommand. JSON mode writes the original successful API bytes
plus one `LF`; stable exits are `0` success/idempotence, `1` unexpected local
failure, `2` usage/configuration, `3` typed business outcome not achieved, and
`4` service/transport/protocol failure.

HTTP status is never a business-state channel. Every valid request that reaches
a business outcome returns `200 OK`, including pending, stale, partial,
unavailable, rejected, not-found, degraded, and unhealthy results. `4xx` is
reserved for request, browser-security, route, method, media-type, or other
transport-contract errors; `500` is an unexpected server failure. V1 does not
use `202`, `409`, or `503` for business lifecycle.

## Local configuration

JDK 25 is required. Bitbucket credentials are accepted only from the process
environment:

- `BITBUCKET_USERNAME` is the Atlassian account email used for Basic
  authentication.
- `BITBUCKET_APP_PASSWORD` is a legacy variable name whose value must be a
  current Bitbucket API token, not a retired app password. Identity lookup needs
  at least `read:user:bitbucket`; the complete repository-read scope set is
  provider-side configuration and is not encoded by this application.

Credentials are not accepted by the product CLI, configuration API, config
file, database, or notification arguments. Do not put credential values on a
command line or in checked-in files.

Non-secret runtime settings use environment-over-file precedence:

| Environment override | `application.conf` fallback | Default |
|---|---|---|
| `BITBUCKET_HELPER_HTTP_PORT` | `bitbucket-helper.http.port` | `8080` |
| `BITBUCKET_HELPER_DATABASE_PATH` | `bitbucket-helper.database.path` | `./var/bitbucket-helper.sqlite` |
| `BITBUCKET_HELPER_UNIX_SOCKET_PATH` | `bitbucket-helper.unix-socket.path` | `./var/bitbucket-helper.sock` |
| `BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE` | `bitbucket-helper.notification.executable` | `/usr/local/bin/desktop-notifications` |
| `BITBUCKET_HELPER_LOG_LEVEL` | `bitbucket-helper.logging.level` | `DEBUG` |
| `BITBUCKET_HELPER_LOG_DIRECTORY` | `bitbucket-helper.logging.directory` | `./var/log` |

The browser host is fixed to `127.0.0.1`. The Bitbucket request timeout comes
from `bitbucket-helper.bitbucket.request-timeout` and defaults to `PT30S`.
Relative file fallbacks are resolved from the service working directory.

The service validates all paths before opening runtime resources. In particular,
the Unix-socket parent must already be a real current-user-owned directory with
exact mode `0700` and secure directory access; the resolved notification
provider path must identify an executable regular file; and the SQLite target
and its parent must be writable, non-symlink locations suitable for SQLite
journal files. Product commands discover only the Unix socket, using its
environment override or config fallback without loading credentials or
validating unrelated service paths.

See [the manual local runbook](docs/operations/manual-service-run.md) for an
offline-capable build, safe foreground startup, verification, shutdown, and
cleanup. The generic notification boundary and its pinned provider fixture are
documented in
[docs/contracts/desktop-notifications-consumer.md](docs/contracts/desktop-notifications-consumer.md).

### Backend diagnostics

Only `service run` initializes backend Log4j2 logging. Product CLI commands do
not initialize it, change their stdout/stderr contracts, or create a service
log artifact. The default `DEBUG` level is emitted to both the human-readable
terminal output and the JSON Lines file at
`var/log/bitbucket-helper.jsonl` (or the configured log directory). The file
rotates at UTC-day boundaries or 10 MiB, whichever comes first; gzip archives
are retained for 14 days and capped at 200 MiB combined.

Events contain stable names, typed correlation IDs, fixed categories, counts,
durations, statuses, and sanitized exception class/stack-location diagnostics.
They intentionally exclude credentials, headers, cookies, raw paths and
queries, request/upstream bodies, activity Markdown, notification content or
provider output, SQL/bind values, absolute paths, exception messages, and
arbitrary object text. Log severity is diagnostic only and never changes the
versioned response body or HTTP status semantics.

## Privacy boundary

Credentials are never persisted, echoed, sent through API or CLI fields, placed
in notification argv, or included in health, error, or diagnostic text. Raw
activity, comment, and thread bodies are live-only: the exact requested body can
appear only in a successful live-content response. Raw bodies are absent from
SQLite, bulk dashboard/PR/inbox projections, logs, diagnostics, and notification
arguments. Notification intents persist only generic titles, summaries, safe
links, sounds, and delivery keys.

## Verification

With the required dependencies already present in the local Gradle cache:

```bash
./gradlew --offline clean check verifyApiV1Generated
./gradlew --offline buildFatJar
```

These gates run the Kotlin tests, architecture checks, OpenAPI validation and
generation-drift checks, and build the executable fat JAR without a network
request.

## Architecture and deferred work

The broader boundaries are recorded in the
[architecture specification](docs/superpowers/specs/2026-08-14-bitbucket-assistant-architecture-design.md).
The approved SPA/API design is in the
[API contract specification](docs/superpowers/specs/2026-08-15-spa-kotlin-api-contract-design.md).

The fixture-backed Vue workspace remains disconnected from this service. Vue
integration with the generated API, macOS LaunchAgent installation/start/stop/
update flows, ignored-actor configuration, and a later Testcontainers suite are
explicit follow-ups. This repository does not claim a release, deployment, or
installed background service.

## Shell prototype

The untracked `source/` directory is an independent preserved shell prototype.
It is not part of the Kotlin build or the sibling Python package.

```bash
bash source/tests/run.sh
```
