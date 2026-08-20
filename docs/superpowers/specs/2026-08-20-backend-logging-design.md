# Backend Logging Design

**Date:** 2026-08-20

**Status:** Approved in conversation

**Scope:** Kotlin backend service only

## Purpose

Manual testing currently leaves too little evidence to explain startup,
request, scheduled-job, Bitbucket, persistence, refresh, health-probe, and
notification failures. Several boundaries intentionally convert exceptions to
safe business results, while others discard causes during sanitization or
cleanup. The service therefore needs durable, correlated operational logging
without weakening its existing privacy and HTTP-contract guarantees.

This design adds boundary-centric structured logging backed by Log4j2. The
backend writes the same accepted events to a human-readable terminal appender
and a rotating JSON Lines file appender. Application debug events are enabled
by default and are visible in both destinations.

## Goals

- Make a single manual test traceable from HTTP request through asynchronous
  work and outbound adapters.
- Retain actionable diagnostics for failures that are converted to typed
  results or otherwise sanitized at public boundaries.
- Use stable event names and typed, allowlisted fields that can be searched or
  parsed without scraping prose.
- Preserve credentials, raw activity, upstream payloads, notification content,
  SQL values, and other private data outside every logging surface.
- Preserve the product CLI's exact stdout, stderr, JSON, and exit behavior.
- Make safe logging an explicit, test-enforced contribution requirement in
  `AGENTS.md`.

## Non-goals

- Product CLI command logging.
- Browser or Vue client logging.
- Remote log shipping, OpenTelemetry, distributed tracing, metrics, or alerting.
- Audit logging or a guarantee that log writes are durably synced to physical
  storage before a business operation completes.
- Logging HTTP bodies, Bitbucket bodies, notification content, provider output,
  SQL text, bind values, or arbitrary object renderings.
- Changing HTTP response schemas, business outcomes, or status semantics.
- Implementing the deferred `service logs` command or LaunchAgent lifecycle.

## Chosen approach

The service will use boundary-centric structured logging.

Framework-only logging is insufficient because it cannot see causes that the
application maps to typed results. Pervasive method-entry/method-exit logging is
rejected because it creates noise, increases privacy risk, and couples internal
implementation details to an operational contract. The chosen approach records
the terminal outcome at each meaningful boundary and adds narrower events only
where asynchronous registration or retry decisions need correlation.

## Logging stack

- Pin Apache Log4j2 `2.26.1` through the Log4j BOM.
- Use Log4j Core as the backend.
- Use `log4j-slf4j2-impl` so Ktor, Quartz, and other SLF4J 2 callers flow into
  Log4j2.
- Use Log4j JSON Template Layout for the JSON Lines appender.
- Application-owned code logs through the SLF4J 2 API with fluent key/value
  fields; it does not depend on Log4j Core outside backend bootstrap and
  configuration tests.
- Do not install broad HTTP-client body/header logging or a JUL bridge as part
  of this change.

The application package logger is `DEBUG` by default. The root logger remains
at `INFO`, with explicit third-party overrides where needed to prevent SQL,
wire, header, or payload diagnostics. In particular, jOOQ SQL diagnostics and
generated/Ktor HTTP wire diagnostics must not become visible merely because
application debug logging is enabled.

## Service-only initialization

Logging is initialized only from `service run`, before persistence, clients,
the scheduler, or servers are opened. Product commands do not initialize the
service logging bootstrap and must not create the service log directory or log
file.

The classpath Log4j2 fallback configuration is inert (`OFF` and
create-on-demand) until the service bootstrap supplies validated properties and
explicitly reconfigures Log4j2. This protects product commands even if a client
dependency touches SLF4J. Tests must cover a product command in a fresh process
and prove that it creates no backend log artifact.

The service bootstrap owns Log4j shutdown and flushes appenders after the final
shutdown event. Initialization failures use the existing fixed terminal error
surface. Once logging is active, startup and shutdown failures are recorded
before being rethrown to the existing process-level handling.

## Configuration and outputs

The service accepts these non-secret settings with environment-over-file
precedence:

| Environment override | `application.conf` key | Default |
|---|---|---|
| `BITBUCKET_HELPER_LOG_LEVEL` | `bitbucket-helper.logging.level` | `DEBUG` |
| `BITBUCKET_HELPER_LOG_DIRECTORY` | `bitbucket-helper.logging.directory` | `./var/log` |

Accepted levels are `TRACE`, `DEBUG`, `INFO`, `WARN`, and `ERROR`, parsed
case-insensitively and normalized. Blank or unsupported values are startup
configuration errors naming only the setting.

Both appenders accept the same application events at the configured level:

1. **Terminal:** UTF-8, human-readable, UTC timestamp, level, event name,
   concise message, and compact structured context. Control characters in
   values are escaped. The service writes no product result to this process,
   so the terminal appender may use stderr consistently with existing startup
   diagnostics.
2. **File:** UTF-8 JSON Lines at
   `<log-directory>/bitbucket-helper.jsonl`, using a custom JSON event template.
   Each physical line is one parseable JSON object.

The file appender rotates at UTC day boundaries or when the active file reaches
10 MiB, whichever occurs first. Archives are gzip-compressed, retained for at
most 14 days, and constrained to a combined 200 MiB. Cleanup is limited to the
validated log directory, does not follow links, and matches only the
application's archive filename pattern.

Appenders are synchronous. The expected volume is low, diagnostic reliability
is the priority, and no measured workload currently justifies queueing or the
risk of losing the final events around a crash. A future change may introduce
asynchronous appenders only with benchmark evidence and explicit overflow and
shutdown tests.

## Secure log storage

The logging directory is resolved to an absolute normalized path before Log4j2
starts. It must be a real, current-user-owned, writable directory with exact
mode `0700`, a non-symbolic-link identity, secure directory access, and
replacement-safe ancestry consistent with the existing SQLite and Unix-socket
policy. A missing final log directory may be created with mode `0700` only
beneath a validated parent. Unsafe existing entries are never replaced.

The active file and archives use exact mode `0600`. Log4j2 rolling-file POSIX
permissions are configured explicitly and verified after initialization.
Startup stops before other runtime resources open if the directory or active
file cannot be prepared safely. Runtime appender failures are reported through
Log4j2's status logger to the terminal; logging failures must not silently
switch to an unsafe directory or broaden permissions.

Paths are configuration data but are not useful event fields. Events identify
the affected component or setting, not the absolute database, socket,
executable, or log path.

## Event schema

Every JSON event contains:

- UTC timestamp;
- severity level;
- stable `event` name;
- logger name;
- fixed human-readable message;
- `service_instance_id` once allocated;
- zero or more typed, allowlisted correlation and outcome fields;
- sanitized exception diagnostics only for unexpected failures.

Field names use lower snake case. Event names use lower dot-separated names.
Event names and enum-like field values are constants, not class simple names,
localized strings, or arbitrary exception messages. Numeric values remain JSON
numbers and booleans remain JSON booleans.

The terminal and JSON appenders receive the same `LogEvent`; the layouts may
render it differently but must not apply different severity filters or omit an
application event from one destination.

## Correlation model

- `service_instance_id` is created before runtime composition and is present on
  all service events after logging initialization.
- `request_id` reuses the existing cryptographically generated API V1 response
  identifier. Client-provided request IDs are not accepted.
- `refresh_run_id` connects refresh registration, monitoring, and completion.
- `repository_id` connects refresh-run entries with Bitbucket and persistence
  outcomes.
- `scheduler_execution_id` is generated for each scheduled invocation and is
  paired with the fixed scheduled use-case key.
- `notification_intent_id` and `notification_attempt_id` connect delivery and
  retry decisions.

Request-scoped context may use the Ktor/SLF4J MDC integration while the call is
active. Asynchronous and independently scoped coroutine work must carry
correlation values explicitly in typed events; correctness must not rely on a
thread-local MDC surviving dispatcher changes.

A tester must be able to search a request ID, discover a refresh-run ID in the
request outcome, and then follow each repository's registration and terminal
outcome. Joined refresh flights may be referenced by more than one refresh run;
each monitor records its own run/repository correlation.

## Event catalog and levels

The exact constants will live in one observability package. The minimum catalog
is:

| Event | Normal level | Required context |
|---|---:|---|
| `service.starting` | INFO | version, configured level |
| `service.started` | INFO | browser port |
| `service.stopping` | INFO | shutdown reason category |
| `service.stopped` | INFO | duration |
| `service.configuration.failed` | ERROR | safe setting code, sanitized exception |
| `service.start.failed` | ERROR | component, sanitized exception |
| `service.stop.failed` | ERROR | component, sanitized exception |
| `http.request.completed` | DEBUG for reads, INFO for mutations | request ID, transport, method, fixed operation, status, outcome, duration |
| `http.request.rejected` | WARN | request ID, transport, method, fixed operation, status, request-error code, duration |
| `http.request.failed` | ERROR | request ID, transport, method, fixed operation, status, duration, sanitized exception |
| `scheduler.started` / `scheduler.stopped` | INFO | scheduler state |
| `scheduler.job.started` | DEBUG | execution ID, fixed job key |
| `scheduler.job.completed` | INFO | execution ID, fixed job key, duration, safe summary |
| `scheduler.job.timed_out` | WARN | execution ID, fixed job key, duration |
| `scheduler.job.failed` | ERROR | execution ID, fixed job key, duration, sanitized exception |
| `refresh.run.registered` | INFO | request ID when present, run ID, repository count, disposition counts |
| `refresh.repository.completed` | INFO | run ID when present, repository ID, outcome, duration |
| `refresh.repository.partial` / `refresh.repository.failed` | WARN | run ID when present, repository ID, failure category/count, retryability, duration |
| `refresh.repository.deferred` | INFO | run ID, repository ID, retry time |
| `bitbucket.request.completed` | DEBUG | fixed operation, repository ID when present, status, duration |
| `bitbucket.request.failed` | WARN | fixed operation, repository ID when present, safe category, retryability, status when available, duration |
| `notification.delivery.completed` | INFO | intent ID, attempt ID/number, result, retry decision, duration |
| `notification.delivery.failed` | WARN | intent ID, attempt ID/number, safe category, ambiguity, retry decision, duration |
| `persistence.transaction.failed` | ERROR | fixed calling operation/component, sanitized exception |
| `health.probe.failed` | WARN | component, sanitized exception |

High-frequency successful reads and polling remain `DEBUG`. They are visible in
both outputs by default because the approved default is `DEBUG`. Raising
`BITBUCKET_HELPER_LOG_LEVEL` raises both destinations together. Known business
outcomes keep their domain meaning: an unavailable, deferred, stale, partial,
or rejected result can still be HTTP `200`; severity does not encode HTTP or
business semantics.

The implementation may add narrowly scoped events only if they follow the same
naming, field, privacy, and testing rules. It must not add method-entry events,
payload dumps, or success logs for individual SQL statements.

## HTTP instrumentation

The API uses a dedicated observability plugin rather than Ktor's default access
log formatting, because generic access logs can include raw URIs or query
strings. Each known route assigns a fixed operation constant and a fixed result
type. Unknown routes use `route_not_found`; their raw path is not recorded.

Exactly one terminal HTTP event is emitted per API request:

- successful reads and polling: `http.request.completed` at DEBUG;
- successful mutations and refresh registration: `http.request.completed` at
  INFO;
- transport/request errors: `http.request.rejected` at WARN;
- unexpected server errors: `http.request.failed` at ERROR.

The event includes method but excludes raw URI, path parameters as a combined
path, query string, headers, cookies, and body. Separately allowlisted IDs that
were successfully validated may be included in named fields. StatusPages keeps
the current safe response envelopes and records the event context without
changing any response.

## Background and adapter instrumentation

### Application event port

Most boundary events belong in bootstrap or adapters. Where application
services currently convert or swallow a failure while holding essential
business correlation, they emit a sealed, typed `OperationalEvent` through an
application outbound port. Each event type exposes only its named stable IDs,
enums, counts, timestamps, and an optional original `Throwable` for the logging
adapter to sanitize. There is no generic map, free-form message, or arbitrary
object field. Domain code remains logging-free and application code has no
logging-framework dependency.

Production composition supplies a Log4j-backed event recorder. Unit tests may
use a recording or no-op implementation explicitly.

### Scheduler

Each Quartz invocation receives a generated execution ID. Start, completion,
timeout, interruption, and unexpected failure retain the fixed use-case key and
duration. The current fixed `System.err` diagnostic is removed. Interrupt status
is still restored, cancellation semantics remain unchanged, and Quartz receives
the same sanitized `JobExecutionException` contract while the private log keeps
safe stack information.

### Bitbucket

Existing result-mapping boundaries record fixed operation names, duration,
status when safely available, and the mapped gateway failure category. They do
not install Ktor client wire logging. Authentication material, URLs, query
parameters, request/response headers, response bodies, Jackson excerpts, and
generated DTO renderings are never fields. Unexpected exceptions are sanitized
before logging and then follow the existing typed mapping.

### Persistence and health

The jOOQ transaction boundary records unexpected transaction failures without
SQL text or bind values and rethrows them unchanged. Successful transactions
are not individually logged. Startup/open/close state is represented by service
lifecycle events.

Health probes that currently convert an exception to an unhealthy component
record the component and safe exception diagnostics before returning the same
health snapshot.

### Refresh and notifications

Refresh registration records run and disposition counts. Every monitored
run/repository pair records one terminal result, including unexpected failures
that are currently converted to a generic synchronization failure.

Notification delivery records only intent/attempt identifiers, attempt number,
safe result category, ambiguity, retry decision, and duration. It never records
delivery keys, titles, bodies, sounds, open URLs, executable paths, command
arguments, stdout, or stderr. Cancellation and claim-cleanup failures retain
safe diagnostics and preserve current cancellation behavior.

## Safe exception diagnostics

The original `Throwable` is never passed directly to a Log4j2 appender. Library
exception messages can contain HTTP content, URLs, SQL, filesystem paths, or
other private values.

The observability adapter converts an unexpected throwable into a bounded,
immutable diagnostic representation containing only:

- exception class names;
- ordered cause and suppressed-exception relationships;
- stack-frame class, method, file, and line locations;
- truncation metadata.

It excludes every exception message, localized message, arbitrary attached
value, and original object rendering. Limits are 8 cause levels, 8 suppressed
exceptions per level, 64 frames per exception, and 32 KiB for the final encoded
diagnostic field. Cycles are detected by identity. Truncation is explicit.

Known operational failures log their typed category and do not fabricate a
stack trace. Tests inject credentials, raw-content sentinels, newlines, terminal
escape sequences, and control characters into exception messages and prove
that both appenders exclude them while retaining class names and stack-frame
locations.

## Privacy allowlist

Allowed fields are limited to:

- application-generated stable identifiers already validated by domain types;
- fixed operation, component, outcome, disposition, and failure-category
  constants;
- bounded counts and durations;
- HTTP status and method;
- boolean retry/ambiguity flags;
- UTC instants used for retry or lifecycle correlation;
- application version and configured log level;
- sanitized exception diagnostics defined above.

Forbidden data includes:

- usernames, API tokens, authorization values, cookies, environment values;
- raw request paths, URLs, queries, headers, bodies, upstream payloads, Jackson
  source excerpts, comments, threads, and activity Markdown;
- workspace/repository display names, pull-request titles, actor display names,
  commit text, and other user-authored values;
- notification delivery keys, titles, bodies, sounds, URLs, executable paths,
  argv, stdout, and stderr;
- SQL statements, bind values, database rows, and configuration-object output;
- absolute resource paths;
- exception messages and arbitrary `toString()` output.

Console layout escapes C0/C1 controls, DEL, ANSI escape characters, carriage
returns, and line feeds in values. JSON layout emits valid escaped JSON. Fixed
messages contain no interpolated untrusted text.

## Error and lifecycle semantics

Logging is observational. It does not convert business outcomes into transport
failures and does not change retries, backoff, cancellation, cleanup ordering,
or public error messages.

HTTP status rules remain:

- `200 OK` for every valid request that reaches a business outcome, including
  pending, stale, partial, unavailable, rejected, or otherwise unsuccessful
  outcomes;
- `4xx` only for request/transport errors;
- `500` only for unexpected server errors;
- no `202` or `409` lifecycle encoding.

Cleanup continues attempting every resource. Each failed component emits a
safe event; the existing primary/suppressed failure behavior remains intact.

## Verification strategy

Implementation follows test-driven development. The minimum automated coverage
is:

### Configuration and storage

- default and overridden level/directory parsing;
- case normalization and invalid-level rejection;
- secure creation, symlink rejection, ownership/mode rejection,
  replacement-safe ancestry, and unwritable-directory rejection;
- active and archived file mode checks;
- XML/JSON Log4j2 configuration loads without status errors;
- rollover configuration contains UTC time and size triggers, gzip archive
  pattern, 14-day deletion, and 200 MiB accumulated-size deletion limited to
  the validated directory.

### Appenders and schema

- a DEBUG event appears in captured terminal output and the JSON file;
- every JSON line parses and preserves numeric/boolean types;
- both destinations contain the same event name and correlation fields;
- control characters cannot inject terminal lines or invalid JSON;
- service shutdown flushes the final event.

### Boundary behavior

- service lifecycle success, startup failure, component cleanup failure;
- one terminal event for successful, rejected, missing-route, and failed HTTP
  requests on browser and Unix transports;
- request ID equality between log event and response envelope;
- request to refresh-run to repository correlation;
- scheduler success, timeout, interruption, unexpected failure, and cleanup;
- Bitbucket typed HTTP/network/malformed failures without wire data;
- transaction and health-probe failures that preserve behavior;
- refresh partial/failed/unexpected terminal outcomes;
- notification accepted, retry, exhausted, timeout, process-start, malformed,
  unexpected-exit, cancellation, and cleanup paths.

### Privacy and CLI isolation

- credentials, Authorization values, cookies, private query markers, raw API
  bodies, upstream bodies/headers, activity Markdown, notification content,
  provider output, SQL/bind sentinels, absolute path sentinels, and malicious
  exception messages are absent from terminal and JSON captures;
- sanitized exception type and stack locations remain present;
- product CLI human and JSON outputs remain byte-identical;
- product commands do not initialize service logging or create log artifacts.

The full existing Kotlin checks, architecture checks, OpenAPI generation drift
checks, fat-JAR build, and relevant web checks remain green.

## Documentation changes

`README.md` will document the two non-secret logging settings and state that
logging belongs only to the backend service.

`docs/operations/manual-service-run.md` will document:

- the default active log path;
- terminal and JSON behavior;
- DEBUG as the default in both destinations;
- rotation and retention;
- safe examples for searching by request, refresh-run, repository, scheduler,
  and notification identifiers;
- owner-only permissions and startup behavior for unsafe paths;
- the privacy boundary and the absence of payloads and exception messages.

## Permanent `AGENTS.md` policy

Add a **Logging and diagnostics** section with these requirements:

- The backend service uses Log4j2 and emits accepted events to both the terminal
  and rotating JSON log. Application logging defaults to DEBUG in both.
- Product CLI commands must not initialize service logging or change their
  stdout/stderr contracts.
- Every new or changed lifecycle, inbound, scheduled/asynchronous, persistence,
  or outbound integration boundary must record a stable terminal outcome with
  the correlation IDs needed to investigate it.
- Asynchronous correlations are explicit; do not assume thread-local MDC
  propagation across coroutines.
- Log only allowlisted stable IDs, fixed categories, counts, durations,
  statuses, and sanitized exception diagnostics. Never log credentials,
  environment values, headers, cookies, raw paths/queries, bodies, upstream
  payloads, user-authored content, notification content/provider output,
  SQL/binds, absolute resource paths, exception messages, or arbitrary objects.
- Domain code remains logging-free. Application code emits only typed events
  through the outbound observability port where a failure would otherwise be
  converted or swallowed.
- Do not use backend `println`, `System.out`, `System.err`, `printStackTrace`,
  generic access/wire logging, or catch-and-discard failure handling. The only
  exception is the fixed process-level terminal fallback before safe service
  logging can initialize.
- Tests for every new event must prove its level, name, required correlations,
  both destinations, terminal behavior, and privacy exclusions on success and
  failure paths.
- Logging severity never replaces versioned response-body business outcomes or
  changes the project's HTTP status semantics.

## Implementation model

The implementation will be executed from a written task plan using the
repository's `sdd_implementer` agent. That agent is configured as
`gpt-5.6-luna` with maximum reasoning effort, as requested. Each scoped task
will be test-first, reviewed, and verified before completion is claimed.

## References

- [Apache Log4j installation and SLF4J bridge](https://logging.apache.org/log4j/2.x/manual/installation.html)
- [Apache Log4j layouts and JSON Template Layout recommendation](https://logging.apache.org/log4j/2.x/manual/layouts.html)
- [Apache Log4j JSON Template Layout](https://logging.apache.org/log4j/2.x/manual/json-template-layout.html)
- [Apache Log4j rolling-file appenders](https://logging.apache.org/log4j/2.x/manual/appenders/rolling-file.html)
- [Apache Log4j asynchronous logging trade-offs](https://logging.apache.org/log4j/2.x/manual/async.html)
