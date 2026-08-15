# Kotlin Walking Skeleton Design

**Date:** 2026-08-15

**Status:** Approved

## Context

Bitbucket Helper needs its first Kotlin/JVM implementation slice. The repository
already has an approved modular-monolith architecture, but it intentionally has no
Kotlin or Gradle foundation yet. This design narrows the first implementation to a
walking skeleton that proves the chosen infrastructure through one coherent flow.

The walking skeleton periodically reads the authenticated Bitbucket Cloud account,
stores the latest connection result in SQLite, and exposes that result through
Ktor. Its purpose is to prove build generation, bootstrap, dependency boundaries,
runtime lifecycle, test seams, and packaging before product pull-request behavior
is introduced.

This is a planning-only design. Project scaffolding and implementation belong to a
separate session and implementation plan. The protected, untracked `source/`
prototype is outside this work and must remain untouched.

## Relationship to the approved architecture

This design refines, but does not replace,
`2026-08-14-bitbucket-assistant-architecture-design.md`.

It preserves these existing decisions:

- one Kotlin/JVM Gradle module and one runnable fat JAR;
- JDK 25;
- package-enforced ports-and-adapters boundaries;
- Ktor with CIO and `kotlinx.serialization`;
- one Clikt command tree with a reserved service-run entrypoint;
- Quartz with `RAMJobStore` as an inbound adapter;
- SQLite behind application ports;
- one long-running process that owns durable state; and
- business behavior entered only through application use cases.

The connection snapshot in this slice is operational supporting state. It is not
a new core-domain aggregate and does not settle the later full persistence design
for pull requests, action items, synchronization checkpoints, or notifications.

## Goals

- Establish a reproducible Gradle Kotlin DSL foundation.
- Pin one compatible dependency baseline in a version catalog.
- Generate the Bitbucket Cloud client from a pinned canonical OpenAPI snapshot.
- Generate jOOQ Kotlin sources from a Liquibase-migrated temporary SQLite database.
- Commit neither OpenAPI-generated nor jOOQ-generated Kotlin code.
- Prove Ktor, Quartz, SQLite, Liquibase, jOOQ, and the Bitbucket adapter together.
- Keep Bitbucket and persistence implementations fakeable behind application ports.
- Fail startup before side effects when required Bitbucket credentials are absent.
- Produce one runnable fat JAR and a clean, offline-from-Bitbucket test suite.

## Non-goals

- Pull-request synchronization, readiness policy, inbox behavior, or notifications.
- The Vue application or the full SPA/backend API contract.
- The complete product CLI; only the reserved service-run path is needed.
- The complete durable schema or the in-memory persistence contract suite.
- A production installation path, LaunchAgent, backup policy, or update mechanism.
- OAuth, token creation, token rotation, or legacy Bitbucket app-password support.
- Flyway or any migration source of truth other than Liquibase XML.
- A persistent Quartz job store, catch-up execution, or a general scheduler framework.
- Live Bitbucket calls in the default automated test suite.
- Custom OpenAPI Generator templates unless a separately approved follow-up requires
  them.
- Testcontainers implementation in this slice.

## Approaches considered

### Bitbucket integration

Three approaches were considered:

1. Generate a Kotlin client from Atlassian's canonical OpenAPI document.
2. Handwrite a narrow Ktor client for the endpoints currently needed.
3. Use an older community JVM wrapper.

The selected approach is OpenAPI generation. It keeps endpoint models and request
construction tied to Atlassian's published contract while a handwritten adapter
protects the application from generated types. A handwritten client would be
smaller initially but would create ongoing schema maintenance. The reviewed
community libraries were either oriented toward Bitbucket Data Center or too old
to become a foundation dependency.

### Database evolution and jOOQ generation

Liquibase XML is the one schema source of truth. jOOQ inspects a temporary SQLite
database to which Liquibase has applied every migration. Maintaining a second DDL
file for code generation was rejected because it could drift. Committing generated
jOOQ sources was rejected because migrations, not generated Kotlin, own the schema.

### Scope of the first slice

A real authenticated-user refresh was selected over infrastructure-only health
probes. It is small enough for a walking skeleton but crosses every required
boundary and produces meaningful persisted state.

## Technology baseline

Versions are centralized in `gradle/libs.versions.toml`. The implementation session
must confirm dependency resolution and compilation together before writing product
behavior; it must not silently upgrade individual components.

| Area | Pinned baseline |
| --- | --- |
| Gradle Wrapper | 9.6.1 |
| Java toolchain and runtime | JDK 25 |
| Kotlin JVM and serialization plugins | 2.4.10 |
| Ktor server/client/plugin | 3.5.1 |
| `kotlinx.serialization-json` | 1.11.0 |
| `kotlinx.coroutines-core` | 1.11.0 |
| Clikt | 5.1.0 |
| Quartz | 2.5.2 |
| Liquibase Community | 5.0.3 |
| jOOQ Open Source and official codegen plugin | 3.21.6 |
| Xerial SQLite JDBC | 3.53.1.0 |
| OpenAPI Generator plugin | 7.24.0 |
| ArchUnit | 1.4.2 |
| JUnit | 6.1.3 |

If the first clean compatibility build disproves any pin, implementation stops and
records the incompatibility instead of choosing an unreviewed replacement. A
planning amendment then selects the smallest compatible change.

## Gradle and distribution shape

The repository has one Gradle project and one JVM module:

```text
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
gradle/wrapper/...
gradlew
gradlew.bat
```

The root `build.gradle.kts` owns Kotlin, application, Ktor fat-JAR, OpenAPI
generation, jOOQ generation, Liquibase preparation, migration validation, and test
configuration. Additional Gradle modules or convention plugins are unnecessary for
this slice.

The application group and base package are:

```text
com.mindtable.bitbuckethelper
```

One `main(args)` function enters the existing Clikt command boundary. The skeleton
implements the reserved `service run` path, plus normal `--help` and `--version`
behavior. `service run` owns the long-running Ktor and Quartz lifecycle. Future
business CLI commands remain service clients and are outside this slice.

The Ktor Gradle plugin produces one executable fat JAR. The manifest names the
single Clikt-backed main class. The required verification commands are:

```text
./gradlew clean check
./gradlew buildFatJar
```

## Walking-skeleton configuration

`application.conf` provides typed, developer-local defaults. Bootstrap validates
and converts every value once rather than letting adapters read environment
variables independently.

| Setting | Default or source |
| --- | --- |
| HTTP host | fixed to `127.0.0.1` |
| HTTP port | `8080`, optionally overridden by `BITBUCKET_HELPER_HTTP_PORT` |
| SQLite path | `./var/bitbucket-helper.sqlite`, optionally overridden by `BITBUCKET_HELPER_DATABASE_PATH` |
| Refresh interval | `PT15M`, optionally overridden by `BITBUCKET_HELPER_REFRESH_INTERVAL` |
| Bitbucket base URL | fixed production default `https://api.bitbucket.org/2.0`; directly injectable in tests |
| Bitbucket request timeout | `PT30S`; directly injectable in tests |

Duration overrides use ISO-8601 duration syntax. Ports must be in the valid TCP
range, the refresh interval must be positive, and the database path must resolve to
a regular file location whose parent can be created or written. These defaults are
for the walking skeleton and do not decide the later installed application's data
directory. The two secret credential values are read directly from their required
environment variables and never stored in `application.conf`.

## Repository and package layout

```text
specs/
└── bitbucket-cloud/
    ├── openapi.json
    └── README.md

src/
├── main/
│   ├── kotlin/com/mindtable/bitbuckethelper/
│   │   ├── domain/
│   │   ├── application/
│   │   │   ├── port/inbound/
│   │   │   ├── port/outbound/
│   │   │   └── service/
│   │   ├── adapter/
│   │   │   ├── inbound/http/
│   │   │   ├── inbound/scheduler/
│   │   │   ├── outbound/bitbucket/
│   │   │   └── outbound/persistence/
│   │   └── bootstrap/
│   └── resources/
│       ├── application.conf
│       └── db/
│           ├── changelog/db.changelog-master.xml
│           └── migration/
│               ├── AGENTS.md
│               └── V0001__create_bitbucket_connection_snapshot.xml
└── test/
    └── kotlin/com/mindtable/bitbuckethelper/...
```

Packages and directories are created when real types require them; empty marker
types are not added merely to populate the tree.

Generated code lives only below `build/`:

```text
build/generated/sources/bitbucket/main/kotlin/...
build/generated/sources/jooq/main/kotlin/...
build/jooq-codegen/bitbucket-helper.sqlite
```

These paths are source-set inputs but Git ignores them. `clean` removes all of
them.

## Dependency rules

The required dependency direction is:

```text
domain <- application <- adapters <- bootstrap
```

- Domain code imports only the Kotlin/JDK standard APIs that its model needs.
- Application code owns use cases, ports, and application-facing models.
- Inbound adapters translate Ktor or Quartz input into use-case calls.
- Outbound adapters translate between application ports and Bitbucket or jOOQ.
- Bootstrap is the only package that constructs concrete adapters and manages
  process lifecycle.
- Generated Bitbucket DTOs stay inside the Bitbucket adapter.
- Generated jOOQ types stay inside the persistence adapter.
- Application and domain packages do not import Ktor, Clikt, Quartz, Liquibase,
  jOOQ, SQLite JDBC, or generated packages.

ArchUnit inspects compiled bytecode and enforces these rules. It also rejects
cycles between the top-level packages and prevents adapters from being imported by
application or domain code.

## Bitbucket OpenAPI source and generation

Atlassian publishes `https://api.bitbucket.org/swagger.json` as the canonical
Bitbucket Cloud OpenAPI definition. The repository commits a reviewed snapshot at
`specs/bitbucket-cloud/openapi.json`. Its adjacent `README.md` records:

- the canonical source URL;
- retrieval date;
- SHA-256 checksum;
- OpenAPI Generator version used to validate it; and
- the explicit update and review procedure.

Normal `check`, compile, and packaging tasks read the committed snapshot and never
download a newer Bitbucket specification. An explicit
`updateBitbucketOpenApiSpec` maintenance task may download a candidate, but it is
not a dependency of any normal build. Updating the snapshot requires reviewing its
diff and resulting generated API before committing it.

OpenAPI Generator uses the Kotlin generator with the `jvm-ktor` library. Generation
is restricted to the operation identified by HTTP method `GET` and Bitbucket path
`/user` under the specification's `/2.0` base path, together with the models it
requires. The selection is based on the pinned specification rather than a guessed
generated method name.

The generated package is beneath:

```text
com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated
```

The generated client uses Jackson internally because the canonical Bitbucket
schema makes broad use of inheritance and discriminators. Jackson remains private
to the adapter; Ktor server responses continue to use `kotlinx.serialization`.
Generated Ktor dependencies are aligned to the project's Ktor 3.5.1 pin.

The first implementation task is a code-generation compilation spike. It must
prove that the pinned specification, selected operation, generated models, Ktor
3.5.1, and JDK 25 compile without modifying generated files or maintaining custom
templates. If `jvm-ktor` fails that test, the approved fallback is the generator's
`jvm-okhttp4` library. The application port and adapter mapping do not change.

## Bitbucket credentials and client boundary

The process reads exactly these required environment variables:

| Variable | Meaning |
| --- | --- |
| `BITBUCKET_USERNAME` | Atlassian account email used as the Basic-auth username |
| `BITBUCKET_APP_PASSWORD` | Legacy variable name containing a current scoped Bitbucket API token |

`BITBUCKET_APP_PASSWORD` must not contain a retired Bitbucket app password. The
token needs the minimum `read:user:bitbucket` scope for `GET /2.0/user`.

Both values must be present and nonblank. Bootstrap validates them before opening
or creating the SQLite database. A missing value is a startup-fatal configuration
error, reported by variable name only. The value itself is never included in a log,
exception, HTTP response, database row, generated-client diagnostic, or test
failure message.

The handwritten `BitbucketAccountGateway` outbound port exposes one operation that
returns an application-owned account model. `GeneratedBitbucketAccountGateway` is
the only handwritten production class allowed to import generated Bitbucket
types. It:

- configures Basic authentication with email and token;
- supports an injectable base URL so tests can use a local fake server;
- applies bounded connection and request timeouts;
- disables generated HTTP body/header logging;
- maps the generated response immediately into the application model; and
- maps upstream failures into sanitized application failure categories.

The production base URL is `https://api.bitbucket.org/2.0`. There is no startup
network health check. Presence of credentials is startup-fatal; rejection of
present credentials by Bitbucket is a recoverable scheduled-refresh result.

## Walking-skeleton application behavior

The slice implements one flow:

```text
Quartz trigger
  -> RefreshBitbucketConnection use case
  -> BitbucketAccountGateway
  -> generated GET /2.0/user client
  -> BitbucketConnectionRepository
  -> SQLite through jOOQ

GET /api/v1/bitbucket/status
  -> GetBitbucketConnectionStatus use case
  -> BitbucketConnectionRepository
  -> versioned JSON response
```

Application boundaries are:

- `RefreshBitbucketConnection`: inbound use case that performs one attempt and
  records its outcome.
- `GetBitbucketConnectionStatus`: inbound query returning the current snapshot.
- `BitbucketAccountGateway`: outbound port for the authenticated account.
- `BitbucketConnectionRepository`: outbound port for loading and atomically
  updating the singleton snapshot.

The gateway's successful application model contains only the stable account UUID,
display name, and optional nickname required by this slice. It does not expose a
generated DTO.

The singleton connection snapshot contains:

- state: `healthy` or `failed` when a row exists;
- account UUID, display name, and optional nickname from the last success;
- last-attempt timestamp;
- last-success timestamp; and
- optional sanitized failure code and message.

No row means `pending`. A successful attempt upserts the current account, marks the
snapshot healthy, updates both timestamps, and clears the previous failure. A
failed attempt marks the snapshot failed and updates the attempt and failure fields
while preserving any last-known-good account and success timestamp.

Failure codes are a small stable set: authentication, authorization, rate limited,
timeout, network, upstream, and unexpected. Messages are deliberately generic and
never contain raw response bodies or authorization material.

## Ktor endpoint semantics

`GET /api/v1/bitbucket/status` is the one walking-skeleton operational endpoint.
It scopes only this diagnostic response and does not pre-approve the later SPA
product API.

The response body has an explicit schema version and represents:

```json
{
  "schemaVersion": 1,
  "state": "pending | healthy | failed",
  "lastAttemptAt": null,
  "lastSuccessAt": null,
  "account": null,
  "failure": null
}
```

When present, `account` contains UUID, display name, and nickname. When present,
`failure` contains only the sanitized code and message. Timestamps are UTC instants
serialized in ISO-8601 form.

Following the repository's HTTP status guidance, every successfully processed
status query returns `200 OK`, including pending or failed business state. Request
errors use `4xx`; an unexpected server failure uses `500`. The route does not use
`202`, `409`, or `503` to encode the connection state.

## Liquibase XML migrations

Every migration file is Liquibase XML. SQL migration files are not allowed.

The master file is:

```text
src/main/resources/db/changelog/db.changelog-master.xml
```

It uses `includeAll` over `db/migration` with an `.xml` suffix filter. Liquibase
runs included files alphabetically. Versioned filenames therefore use exactly:

```text
V%04d__<snake_case_description>.xml
```

The Gradle validator applies this regular expression:

```text
^V[0-9]{4}__[a-z0-9]+(?:_[a-z0-9]+)*\.xml$
```

It additionally rejects version `0000` and duplicate numeric version prefixes.
Version gaps are allowed so a reverted, unmerged branch does not force renumbering
of every later migration.

The first migration is:

```text
V0001__create_bitbucket_connection_snapshot.xml
```

Each file contains uniquely identified Liquibase `changeSet` elements and an
explicit rollback where practical. Once applied, a migration is immutable; every
later change is a new versioned file.

Implementation creates
`src/main/resources/db/migration/AGENTS.md`. It records the XML-only requirement,
filename rule and example, changeset-ID uniqueness, rollback expectation, and
immutability rule. The master's suffix filter excludes `AGENTS.md` from Liquibase.

`validateMigrationNames` is part of `check` and runs before either build-time or
test migrations.

## SQLite schema and jOOQ

`V0001__create_bitbucket_connection_snapshot.xml` creates one singleton table with
this shape:

| Column | SQLite type | Rule |
| --- | --- | --- |
| `singleton_id` | `INTEGER` | primary key, always `1` |
| `state` | `TEXT` | `healthy` or `failed` |
| `account_uuid` | `TEXT` | required after a success |
| `display_name` | `TEXT` | required after a success |
| `nickname` | `TEXT` | optional |
| `last_attempt_at` | `TEXT` | required UTC ISO-8601 instant |
| `last_success_at` | `TEXT` | required after a success |
| `failure_code` | `TEXT` | required in failed state |
| `failure_message` | `TEXT` | required in failed state and sanitized |

Database checks constrain the singleton key, allowed state values, and the
state-dependent required fields. Account columns remain populated after a later
failure so the repository preserves last-known-good identity. The repository
converts timestamps at the adapter boundary.

jOOQ code generation follows this build graph:

```text
validateMigrationNames
  -> prepareJooqCodegenDatabase
  -> jooqCodegen
  -> compileKotlin
```

`prepareJooqCodegenDatabase` always creates a new SQLite database under `build/`
and applies `db.changelog-master.xml` from an empty state. The official jOOQ Gradle
plugin then runs `org.jooq.codegen.KotlinGenerator` against the resulting SQLite
metadata and writes Kotlin sources below `build/generated/sources/jooq`.

The generated package is:

```text
com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated
```

`compileKotlin` depends on both OpenAPI and jOOQ generation. CI starts from an empty
`build/` directory, so compilation proves that the entire migration chain can
reconstruct the schema and the generated sources.

At runtime bootstrap opens the configured SQLite file and runs Liquibase before
constructing the jOOQ repository. Every connection enables SQLite foreign-key
enforcement and a bounded busy timeout. Runtime jOOQ uses `SQLDialect.SQLITE`.
Blocking JDBC work initiated by Ktor runs on a bounded database dispatcher rather
than a Ktor event-loop thread. The repository owns the transaction that atomically
updates the singleton snapshot and maps generated records into application models.

## Quartz scheduling

Quartz uses `RAMJobStore`; schedules are reconstructed at each process start and no
business state is stored in Quartz.

The walking skeleton registers one refresh job and one simple repeating trigger.
The trigger fires immediately and repeats at a configurable interval whose default
is 15 minutes. Its misfire policy skips catch-up bursts and resumes with the next
scheduled execution.

`RefreshBitbucketConnectionJob` contains no business logic. A bootstrap-owned job
factory supplies the inbound use case without a global service locator. The job is
marked `@DisallowConcurrentExecution`. It bridges Quartz's synchronous job API to
the suspending use case on the Quartz worker with a bounded timeout and waits for
completion. It never starts detached or global coroutine work.

A recoverable refresh failure is persisted and considered a completed job attempt;
the next scheduled trigger tries again. An inability to persist the result is
logged in sanitized form and surfaced through operational diagnostics, but does not
create an unbounded immediate-retry loop.

## Startup and shutdown

Startup order is:

1. Parse typed configuration.
2. Validate both required credential variables as present and nonblank.
3. Open SQLite and run Liquibase.
4. Construct jOOQ repositories, Bitbucket client/adapter, and use cases.
5. Create Quartz, register the job and trigger, and prepare Ktor.
6. Start Quartz and Ktor only after every preceding step succeeds.

Startup is aborted with a nonzero exit for missing configuration, an unusable
database, migration failure, scheduler initialization failure, or HTTP bind
failure. Diagnostics name the failed component without secret values. If a later
startup step fails, bootstrap closes already-created resources in reverse order.

Runtime Bitbucket authentication or authorization rejection, rate limiting,
timeouts, network errors, and upstream `5xx` responses do not stop the service.
They produce a sanitized failed snapshot and the normal schedule continues.

Graceful shutdown:

1. stops Ktor from accepting new work;
2. stops Quartz with a bounded wait for the active job;
3. closes the Bitbucket HTTP client; and
4. closes database resources.

The same cleanup path handles partial startup.

## Security and diagnostic rules

- Credentials exist only in process configuration and the in-memory HTTP
  authentication setup.
- Generated-client request/header/body logging is disabled.
- Application logs never interpolate credential-bearing configuration objects.
- Raw Bitbucket response bodies are not persisted or returned.
- Failure mapping allows only the approved sanitized code and generic message.
- Tests use a distinctive sentinel token and fail if it appears in captured logs,
  exceptions, HTTP responses, or database content.
- The status endpoint binds through the existing loopback-only service policy.

## Verification strategy

### Unit and boundary tests

- Configuration tests cover each missing and blank credential independently.
- Application tests use in-memory fake gateway and repository implementations.
- Refresh tests cover success, authentication rejection, rate limiting, timeout,
  network failure, sanitization, and preservation of last-known-good state.
- ArchUnit tests enforce package direction and generated-type isolation.
- Ktor route tests cover pending, healthy, failed, and unexpected repository error
  responses.

### Adapter integration tests

- The generated-client contract test starts a local fake Bitbucket HTTP server on
  an ephemeral port.
- It verifies `GET /2.0/user`, the exact Basic-auth value produced from email and
  token, response mapping, timeout behavior, and sanitized error mapping.
- Liquibase tests migrate a new temporary SQLite database and verify the expected
  schema and changesets.
- jOOQ repository tests use the migrated database and generated Kotlin types.
- Quartz tests use `RAMJobStore`, an immediate trigger, and deterministic
  completion coordination rather than fixed sleeps.

### Full walking-skeleton test

The end-to-end test:

1. starts a fake Bitbucket server on an ephemeral port;
2. starts the production composition with test credentials and a temporary SQLite
   file;
3. lets the immediate Quartz job call the real OpenAPI-generated adapter;
4. waits deterministically until jOOQ has persisted the account snapshot;
5. calls `GET /api/v1/bitbucket/status` and verifies the healthy persisted account;
6. inspects captured logs and persisted data for the sentinel token and
   Authorization header; and
7. shuts the application down cleanly without contacting Bitbucket Cloud.

A separate process-level test launches the fat JAR without each credential in turn
and proves a nonzero exit occurs before the configured SQLite file is created.

## Acceptance criteria

- `./gradlew clean check` succeeds with an empty `build/` directory.
- The clean build validates the pinned Bitbucket specification and generates both
  OpenAPI and jOOQ Kotlin sources.
- Normal build and test tasks do not download the Bitbucket specification or call
  Bitbucket Cloud.
- `./gradlew buildFatJar` produces one runnable JAR with the Clikt-backed main
  entrypoint.
- The full test proves Quartz -> generated Bitbucket client -> application use
  case -> jOOQ/SQLite -> Ktor end to end.
- Application unit tests prove the Bitbucket gateway is independently fakeable.
- Missing credentials fail before database creation.
- Pending, healthy, and failed status bodies all use `200 OK` for a valid query.
- The sentinel API token is absent from logs, exceptions, responses, and the
  database.
- Generated sources and temporary SQLite files are not tracked by Git.
- The implementation does not modify or stage `source/` or unrelated user changes.

## Explicit deferred TODO: Testcontainers integration suite

In a separate follow-up design and implementation slice:

- introduce a dedicated Testcontainers-backed `integrationTest` source set and
  Gradle task;
- exercise the packaged application against appropriate containerized external
  collaborators, such as a Bitbucket HTTP stub;
- keep SQLite as a temporary file because it is an embedded database; and
- decide the precise container topology and CI/Docker prerequisites in that
  follow-up.

This TODO is intentionally not an acceptance criterion for the walking skeleton.
The current implementation must not add Testcontainers dependencies, tasks, or
tests.

## Primary references

- [Bitbucket Cloud REST API introduction and canonical OpenAPI specification](https://developer.atlassian.com/cloud/bitbucket/rest/intro/)
- [Bitbucket current-user endpoint](https://developer.atlassian.com/cloud/bitbucket/rest/api-group-users/)
- [Bitbucket API tokens](https://support.atlassian.com/bitbucket-cloud/docs/api-tokens/)
- [Bitbucket app-password retirement](https://support.atlassian.com/bitbucket-cloud/docs/using-app-passwords/)
- [OpenAPI Generator Kotlin client](https://openapi-generator.tech/docs/generators/kotlin/)
- [OpenAPI Generator Gradle plugin](https://openapi-generator.tech/docs/plugins/)
- [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html)
- [Kotlin releases](https://kotlinlang.org/docs/releases.html)
- [Ktor fat-JAR packaging](https://ktor.io/docs/server-fatjar.html)
- [jOOQ official Gradle code-generation plugin](https://www.jooq.org/doc/latest/manual/code-generation/codegen-execution/codegen-gradle/)
- [jOOQ generator configuration](https://www.jooq.org/doc/latest/manual/code-generation/codegen-configuration/)
- [Liquibase `includeAll`](https://docs.liquibase.com/secure/reference-guide-5-0/changelog-attributes/includeall)
- [Xerial SQLite JDBC releases](https://github.com/xerial/sqlite-jdbc/releases)
- [Quartz releases](https://github.com/quartz-scheduler/quartz/releases)
- [Clikt](https://github.com/ajalt/clikt)
- [ArchUnit](https://github.com/TNG/ArchUnit)
- [JUnit 6.1.3 user guide](https://docs.junit.org/6.1.3/overview.html)
