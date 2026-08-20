# Backend Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add privacy-safe, correlated Log4j2 logging to the long-running Kotlin backend, with every application DEBUG event visible in both the terminal and a secure rotating JSON Lines file.

**Architecture:** Initialize an inert-by-default Log4j2 stack only inside `service run`, then emit typed boundary events through a framework-free application port and a shared observability adapter. Use a custom Ktor API observer and explicit asynchronous correlation instead of generic access/wire logging or thread-local assumptions. Preserve every existing API, CLI, retry, cancellation, and cleanup contract.

**Tech Stack:** Kotlin 2.4.10, JDK 25, Gradle 9.6.1, SLF4J 2.0.18, Apache Log4j2 2.26.1 (`log4j-core`, `log4j-slf4j2-impl`, `log4j-layout-template-json`), Ktor 3.5.1 CIO, Kotlin coroutines 1.11.0, Quartz 2.5.2, jOOQ 3.21.6, SQLite JDBC 3.53.1.0, JUnit 6.1.3, and `kotlinx.serialization-json` 1.11.0.

**Spec:** `docs/superpowers/specs/2026-08-20-backend-logging-design.md`

## Global Constraints

- Implement exactly the approved backend-logging spec; do not add CLI logging, browser logging, remote shipping, metrics, tracing, audit logging, or `service logs`.
- Use Apache Log4j2 `2.26.1` through its BOM and the resolved SLF4J API `2.0.18`; do not substitute Logback, Log4j 1.x, JUL, or a different Log4j line.
- Initialize service logging only inside `service run`. Product CLI commands must not create the logging directory/file or change existing stdout, stderr, JSON bytes, or exits.
- Default the application logger to `DEBUG`; both the terminal and rotating JSON appenders receive the same accepted application events. `BITBUCKET_HELPER_LOG_LEVEL` raises or lowers both together.
- Keep the root logger at `INFO` and explicitly suppress jOOQ SQL diagnostics and Ktor/generated-client wire diagnostics. Never install body/header logging or a JUL bridge.
- Use stable lower-dot event names and lower-snake field names. Do not derive either from runtime class names, exception messages, or arbitrary object rendering.
- Domain code remains logging-free. Application services may emit only sealed typed `OperationalEvent` values through `OperationalEventRecorder`; they must not import SLF4J or Log4j.
- Pass asynchronous identifiers explicitly. Never depend on MDC surviving a coroutine dispatcher or independent service-scope launch.
- Never log credentials, environment values, Authorization/Cookie values, raw paths/queries, headers, bodies, upstream content, user-authored values, notification content/provider output, SQL/binds, rows, absolute resource paths, exception messages, or arbitrary `toString()` output.
- The original `Throwable` must never be passed to a logger. Sanitize it to class names, bounded cause/suppressed relationships, bounded stack-frame locations, and explicit truncation metadata.
- Preserve HTTP semantics from `AGENTS.md`: valid business outcomes remain `200`; `4xx` means request/transport error; `500` means unexpected server error; never use `202` or `409` for business lifecycle.
- Preserve scheduler interrupt behavior, coroutine cancellation identity, refresh/backoff/retry decisions, notification claim cleanup, lifecycle cleanup ordering, and primary/suppressed exception behavior.
- Active and archived log files use `0600`; their directory uses `0700`, rejects links and replacement-unsafe ancestry, and never falls back to another location.
- Follow RED-GREEN-REFACTOR. Each task runs the focused and regression commands listed in that task, `git diff --check`, and commits only its own files.
- Preserve unrelated user work. The branch already contains the approved spec and pre-existing SDD-agent configuration; do not rewrite or revert them.
- Execute each implementation task with the repository's `sdd_implementer` role, which is pinned to `gpt-5.6-luna` at maximum reasoning effort.

---

## File and Responsibility Map

### Build and Log4j resources

- `gradle/libs.versions.toml` — Log4j BOM/modules and explicit SLF4J 2.0.18 coordinate.
- `build.gradle.kts` — platform and implementation/runtime dependencies.
- `src/main/resources/application.conf` — logging level/directory defaults.
- `src/main/resources/log4j2.xml` — inert fallback plus terminal/rolling-file appenders, logger thresholds, rollover, retention, and POSIX modes.
- `src/main/resources/bitbucket-helper-log-event.json` — JSON Template Layout schema; no exception resolver because exceptions are pre-sanitized fields.

### Bootstrap and secure initialization

- `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/LoggingConfiguration.kt` — typed level/directory loading and safe setting codes.
- `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/SecureLoggingDirectory.kt` — owner/mode/link/ancestry validation and safe final-directory creation.
- `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceLogging.kt` — set validated Log4j properties, explicit reconfiguration, active-file verification, recorder/session ownership, flush, and shutdown.
- `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Commands.kt` — service-only initialization, shutdown reason, and lifecycle events.
- `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt` — externally supplied service identity/recorder and component-specific lifecycle events.
- `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Main.kt` — retain only the fixed pre-logging process fallback.

### Shared observability boundary

- `src/main/kotlin/com/mindtable/bitbuckethelper/observability/BackendLogEvent.kt` — sealed adapter/bootstrap event types and exact event-level-field mapping.
- `src/main/kotlin/com/mindtable/bitbuckethelper/observability/BackendEventRecorder.kt` — recorder interface, no-op implementation, monotonic time seam, and Log4j-backed implementation.
- `src/main/kotlin/com/mindtable/bitbuckethelper/observability/SafeExceptionDiagnostic.kt` — bounded message-free throwable conversion and console control escaping.
- `src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/OperationalEvents.kt` — sealed application-owned events and framework-free recorder port.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/observability/Log4jOperationalEventRecorder.kt` — exhaustive application-event to backend-event mapping.

### Inbound boundaries

- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Observability.kt` — fixed operations/outcomes, call attributes, monotonic duration, and exactly one terminal event.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Module.kt` — install the custom observer after request-ID assignment.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Errors.kt` — attach request error or sanitized unexpected failure without changing envelopes.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/*Routes.kt` — assign fixed operations/outcomes and validated correlations.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/LocalApiServers.kt` — inject one recorder into both transports.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/SuspendingUseCaseJob.kt` — scheduler execution events; remove fixed `System.err` output.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/ApplicationUseCaseJobFactory.kt` — pass fixed job key, execution ID source, recorder, and time source.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/QuartzApplicationScheduler.kt` — scheduler lifecycle/failure events and recorder composition.

### Application/outbound boundaries

- `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/GetHealthSnapshotService.kt` — typed health-probe failure event before safe degradation.
- `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshRunServices.kt` — refresh registration and each run/repository terminal correlation.
- `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RepositoryRefreshCoordinator.kt` — unexpected flight failure before exceptional completion.
- `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshAllRepositoriesService.kt` — scheduled repository terminal outcomes without a run ID.
- `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/DispatchNotificationsService.kt` — intent/attempt result, retry decision, cancellation cleanup failure, and duration.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketGateway.kt` — fixed upstream operation, safe category/status, duration, and sanitized unexpected exceptions.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketAccountGateway.kt` — preserve delegation and recorder injection.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification/DesktopNotificationsProcessAdapter.kt` — provider-process category/duration without argv or captured output.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqApplicationPersistence.kt` — failed transaction event before unchanged rethrow; never successful SQL events.

### Tests and documentation

- `src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/LoggingConfigurationTest.kt` — parsing and secure directory policy.
- `src/test/kotlin/com/mindtable/bitbuckethelper/observability/SafeExceptionDiagnosticTest.kt` — privacy, bounds, cycles, and terminal escaping.
- `src/test/kotlin/com/mindtable/bitbuckethelper/observability/ServiceLoggingTest.kt` — both outputs, JSON types, modes, fallback isolation, and flush.
- Existing package tests — focused event assertions beside each changed boundary.
- `src/test/kotlin/com/mindtable/bitbuckethelper/BackendLoggingAcceptanceTest.kt` — real composition, end-to-end correlation, privacy sentinels, and product CLI process isolation.
- `src/test/kotlin/com/mindtable/bitbuckethelper/ArchitectureTest.kt` — domain/application logging-framework prohibitions.
- `README.md` and `docs/operations/manual-service-run.md` — configuration and investigation runbook.
- `AGENTS.md` — permanent logging and diagnostics policy from the spec.

---

### Task 1: Log4j Dependencies, Logging Configuration, and Secure Directory

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.conf`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/LoggingConfiguration.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/SecureLoggingDirectory.kt`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/LoggingConfigurationTest.kt`

**Interfaces:**
- Consumes: existing Typesafe `Config`, environment-over-file conventions, and the database path's owner/mode/replacement-safe policy.
- Produces: `ServiceLogLevel`, `LoggingConfiguration`, `LoggingConfigurationLoader.load(config, environment)`, and `SecureLoggingDirectory.prepare(path): Path` for Task 2.

- [ ] **Step 1: Write failing configuration and filesystem tests**

Create `LoggingConfigurationTest.kt` with concrete cases for defaults, environment precedence, case-insensitive levels, blank/unsupported levels, relative normalization, safe final-directory creation, `0700`, existing link rejection, permissive owner/mode rejection, non-writable rejection, secure-directory requirement, and replacement-unsafe ancestry. Use setting sentinels and assert failure text names only `BITBUCKET_HELPER_LOG_LEVEL` or `BITBUCKET_HELPER_LOG_DIRECTORY`.

```kotlin
@Test
fun `defaults are debug and project local var log`() {
    val loaded = LoggingConfigurationLoader.load(defaults, emptyMap())
    assertEquals(ServiceLogLevel.DEBUG, loaded.level)
    assertEquals(Path.of("./var/log").toAbsolutePath().normalize(), loaded.directory)
}

@Test
fun `missing final directory is created owner only below a secure parent`() {
    val parent = Files.createDirectory(directory.resolve("private"))
    Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
    val prepared = SecureLoggingDirectory.prepare(parent.resolve("logs"))
    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(prepared))
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.bootstrap.LoggingConfigurationTest'
```

Expected: test compilation fails because the four logging configuration types do not exist.

- [ ] **Step 3: Pin dependencies and implement the minimal configuration boundary**

Add these exact catalog entries:

```toml
[versions]
log4j = "2.26.1"
slf4j = "2.0.18"

[libraries]
log4j-bom = { module = "org.apache.logging.log4j:log4j-bom", version.ref = "log4j" }
log4j-core = { module = "org.apache.logging.log4j:log4j-core", version.ref = "log4j" }
log4j-slf4j2-impl = { module = "org.apache.logging.log4j:log4j-slf4j2-impl", version.ref = "log4j" }
log4j-layout-template-json = { module = "org.apache.logging.log4j:log4j-layout-template-json", version.ref = "log4j" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
```

Add the BOM with `implementation(platform(libs.log4j.bom))`, compile dependencies on `slf4j-api` and `log4j-core`, and runtime dependencies on `log4j-slf4j2-impl` and `log4j-layout-template-json`. Do not add `log4j-jul`, an async dependency, or any HTTP logging artifact.

Add:

```hocon
bitbucket-helper.logging {
  level = "DEBUG"
  directory = "./var/log"
}
```

Implement these exact types:

```kotlin
enum class ServiceLogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

data class LoggingConfiguration(
    val level: ServiceLogLevel,
    val directory: Path,
)

object LoggingConfigurationLoader {
    fun load(config: Config, environment: Map<String, String> = System.getenv()): LoggingConfiguration
}

internal object SecureLoggingDirectory {
    fun prepare(path: Path): Path
}
```

Parse `BITBUCKET_HELPER_LOG_LEVEL` and `BITBUCKET_HELPER_LOG_DIRECTORY` before any Log4j class is touched. Reuse or extract only the narrow owner/mode/ancestry helpers needed from `ServiceConfiguration.kt`; do not loosen the existing database/socket checks. Allow creation of only the missing final directory entry beneath a validated parent, using a secure directory handle and exact `0700`.

- [ ] **Step 4: Run focused and configuration regression tests**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.bootstrap.LoggingConfigurationTest' --tests 'com.mindtable.bitbuckethelper.bootstrap.ServiceConfigurationTest'
```

Expected: PASS. Inspect the dependency report and confirm exactly one SLF4J provider (`log4j-slf4j2-impl`) and Log4j `2.26.1` modules.

- [ ] **Step 5: Self-review and commit**

Run `git diff --check`, confirm no dependency enables HTTP body/header logging, then commit only Task 1 files:

```bash
git add gradle/libs.versions.toml build.gradle.kts src/main/resources/application.conf src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/LoggingConfiguration.kt src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/SecureLoggingDirectory.kt src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/LoggingConfigurationTest.kt
git commit -m "feat: add secure logging configuration"
```

---

### Task 2: Safe Event Model, Log4j2 Appenders, and Application Event Port

**Files:**
- Create: `src/main/resources/log4j2.xml`
- Create: `src/main/resources/bitbucket-helper-log-event.json`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/observability/BackendLogEvent.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/observability/BackendEventRecorder.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/observability/SafeExceptionDiagnostic.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceLogging.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/OperationalEvents.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/observability/Log4jOperationalEventRecorder.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/ArchitectureTest.kt`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/observability/SafeExceptionDiagnosticTest.kt`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/observability/ServiceLoggingTest.kt`

**Interfaces:**
- Consumes: `LoggingConfiguration` and prepared secure directory from Task 1.
- Produces: `BackendEventRecorder`, `BackendLogEvent` variants, `MonotonicTimeSource`, `ServiceLogging.open(...)`, `ServiceLoggingSession`, `OperationalEvent`, `OperationalEventRecorder`, and `Log4jOperationalEventRecorder` used by Tasks 3-7.

- [ ] **Step 1: Write failing safe-diagnostic and dual-output tests**

Cover exception messages containing a token, URL, SQL, newline, ANSI escape, C1 control, cyclic causes, more than 8 causes, more than 8 suppressed failures, more than 64 frames, and output larger than 32 KiB. Assert the diagnostic contains original class/frame locations but no message sentinel.

Cover a DEBUG lifecycle event emitted through a real `ServiceLoggingSession`: capture stderr, parse each JSON line with `kotlinx.serialization`, compare event/correlation fields across outputs, assert numeric/boolean JSON primitives are not strings, assert active mode `0600`, and close the session before reading the final event.

```kotlin
@Test
fun `debug event reaches terminal and json with typed fields`() {
    val terminalText = captureStandardError {
        ServiceLogging.open(configuration(directory), "svc_test").use { session ->
            session.recorder.record(
                BackendLogEvent.HttpRequestCompleted(
                    requestId = "req_test",
                    transport = "unix",
                    method = "GET",
                    operation = "get_dashboard",
                    status = 200,
                    outcome = "snapshot_unchanged",
                    durationMilliseconds = 7,
                    mutation = false,
                ),
            )
        }
    }
    assertTrue(terminalText.contains("http.request.completed"))
    assertEquals(7, jsonEvents().single().getValue("duration_ms").jsonPrimitive.int)
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.observability.*'
```

Expected: test compilation fails because the observability types and resources do not exist.

- [ ] **Step 3: Implement safe diagnostics and typed events**

Create:

```kotlin
fun interface MonotonicTimeSource {
    fun nanoTime(): Long

    companion object { val SYSTEM = MonotonicTimeSource(System::nanoTime) }
}

fun interface BackendEventRecorder {
    fun record(event: BackendLogEvent)

    companion object { val NONE = BackendEventRecorder { } }
}

data class SafeExceptionDiagnostic(
    val exceptionTypes: List<String>,
    val stackTrace: String,
    val truncated: Boolean,
) {
    companion object { fun from(failure: Throwable): SafeExceptionDiagnostic }
}
```

`SafeExceptionDiagnostic.from` must identity-detect cycles, omit all messages/localized messages, cap cause depth at 8, suppressed entries per level at 8, frames per exception at 64, and encoded stack text at 32 KiB. Escape C0/C1, DEL, ESC, CR, and LF for terminal fields. Do not call `failure.stackTraceToString()`.

Define `BackendLogEvent` as a sealed interface with typed classes for every event in the spec catalog plus the narrow `SchedulerJobInterrupted`, `NotificationProviderCompleted`, `NotificationProviderFailed`, `NotificationCleanupFailed`, and `RefreshRepositoryUnexpected` events consumed later in this plan. Only these failure-bearing variants may carry a `Throwable`: `ServiceConfigurationFailed`, `ServiceStartFailed`, `ServiceStopFailed`, `HttpRequestFailed`, `SchedulerJobFailed`, `PersistenceTransactionFailed`, `HealthProbeFailed`, `BitbucketRequestFailed` when its `unexpectedFailure` is non-null, `NotificationCleanupFailed`, and `RefreshRepositoryUnexpected`. Give every failure-bearing class an explicit redacted `toString()` that includes fixed event identity and safe IDs only. The recorder converts the throwable immediately to `SafeExceptionDiagnostic` and never passes it to SLF4J.

Add `OperationalEvents.kt` with:

```kotlin
fun interface OperationalEventRecorder {
    fun record(event: OperationalEvent)

    companion object { val NONE = OperationalEventRecorder { } }
}

sealed interface OperationalEvent {
    data class HealthProbeFailed(val component: HealthComponent, val failure: Throwable) : OperationalEvent
    data class RefreshRunRegistered(
        val refreshRunId: RefreshRunId,
        val repositoryCount: Int,
        val startedCount: Int,
        val joinedCount: Int,
        val deferredCount: Int,
        val notConfiguredCount: Int,
    ) : OperationalEvent
    data class RefreshRepositoryFinished(
        val refreshRunId: RefreshRunId?,
        val repositoryId: RepositoryId,
        val outcome: RefreshRepositoryOutcome,
        val failureCategory: SynchronizationFailureCategory?,
        val retryable: Boolean?,
        val retryAt: Instant?,
        val durationMilliseconds: Long,
        val unexpectedFailure: Throwable? = null,
    ) : OperationalEvent
    data class NotificationAttemptFinished(
        val intentId: NotificationIntentId,
        val attemptId: NotificationAttemptId,
        val attemptNumber: Int,
        val outcome: NotificationAttemptOutcome,
        val failureCategory: NotificationDeliveryFailureCategory?,
        val ambiguous: Boolean?,
        val retryDecision: NotificationRetryOutcome,
        val durationMilliseconds: Long,
    ) : OperationalEvent
    data class NotificationCleanupFailed(val intentId: NotificationIntentId, val failure: Throwable) : OperationalEvent
}
```

Declare fixed enums `RefreshRepositoryOutcome { SUCCEEDED, PARTIAL, FAILED, DEFERRED, NOT_CONFIGURED, UNEXPECTED }`, `NotificationAttemptOutcome { ACCEPTED, FAILED }`, and `NotificationRetryOutcome { ACCEPTED, RETRY_SCHEDULED, EXHAUSTED }`. `Log4jOperationalEventRecorder` maps every branch exhaustively to a typed `BackendLogEvent`; it has no generic field map.

Implement `HealthProbeFailed`, `NotificationCleanupFailed`, and any `RefreshRepositoryFinished` carrying `unexpectedFailure` as regular classes or with explicit redacted `toString()` implementations. Add unit assertions proving that rendering any failure-bearing operational event cannot expose the original exception message.

Expose the logging session with these exact members so bootstrap code never reaches Log4j Core directly:

```kotlin
interface ServiceLoggingSession : AutoCloseable {
    val recorder: BackendEventRecorder
    val operationalRecorder: OperationalEventRecorder
    override fun close()
}

object ServiceLogging {
    fun open(
        configuration: LoggingConfiguration,
        serviceInstanceId: String,
    ): ServiceLoggingSession
}
```

- [ ] **Step 4: Configure inert Log4j2 and implement the recorder/session**

`log4j2.xml` must default the application and root loggers to `OFF` until these system properties are set by `ServiceLogging.open`: `bitbucketHelper.logging.level`, `bitbucketHelper.logging.directory`, and `bitbucketHelper.service.instance.id`. Configure:

- stderr `Console` with UTC ISO timestamp, level, fixed message/event, and safely formatted key/value context;
- synchronous `RollingFile` at `bitbucket-helper.jsonl`, `createOnDemand=true`, `filePermissions="rw-------"`;
- UTC daily plus 10 MiB triggering, gzip archives, 14-day `IfLastModified`, 200 MiB `IfAccumulatedFileSize`, `followLinks=false`, and filename restriction;
- application namespace at the configured level;
- root at INFO only while service logging is active;
- `org.jooq` WARN, Ktor client/wire/generated client packages INFO or WARN, and no body/header logger;
- no exception resolver in the JSON template.

The JSON template emits timestamp, level, logger, message, event, service instance, correlation/outcome fields, and the sanitized diagnostic fields. Use SLF4J 2 fluent key/value calls and prove via `ServiceLoggingTest` that number and boolean objects stay typed in JSON.

`ServiceLogging.open(configuration, serviceInstanceId, terminal)` prepares the directory, sets properties, explicitly reconfigures Log4j, verifies the active file is a non-link owner-only regular file, returns a `ServiceLoggingSession(recorder, operationalRecorder)`, and on `close()` flushes and stops the `LoggerContext` idempotently.

- [ ] **Step 5: Enforce architecture and run GREEN**

Add ArchUnit rules forbidding `org.slf4j..` and `org.apache.logging.log4j..` dependencies from `..domain..` and `..application..`. The application port file must compile without those imports.

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.observability.*' --tests 'com.mindtable.bitbuckethelper.ArchitectureTest'
```

Expected: PASS with no Log4j Status Logger errors in captured stderr.

- [ ] **Step 6: Self-review and commit**

Run `git diff --check`; search the new code for `message`, `toString`, `Throwable` logger overloads, and environment/path fields. Confirm only the sanitizer reads stack frames and no original throwable reaches SLF4J. Commit Task 2 files:

```bash
git add src/main/resources/log4j2.xml src/main/resources/bitbucket-helper-log-event.json src/main/kotlin/com/mindtable/bitbuckethelper/observability src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceLogging.kt src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/OperationalEvents.kt src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/observability src/test/kotlin/com/mindtable/bitbuckethelper/observability src/test/kotlin/com/mindtable/bitbuckethelper/ArchitectureTest.kt
git commit -m "feat: add structured service logging core"
```

---

### Task 3: Service-Only Bootstrap and Lifecycle Logging

**Files:**
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Commands.kt:39-83`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt:41-272`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Main.kt:7-23`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/RootCommandTest.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntimeLifecycleTest.kt`
- Create: `src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceLoggingBootstrapTest.kt`

**Interfaces:**
- Consumes: `ServiceLogging.open`, both recorders, and lifecycle `BackendLogEvent` types from Task 2.
- Produces: service identity created before runtime composition; logging lifecycle that later adapters share; unchanged root/product command API.

- [ ] **Step 1: Write failing lifecycle and isolation tests**

Add a `ServiceBootstrapSeams` test seam and tests proving:

- help, version, and one product command never invoke `openLogging`;
- `service run` loads logging configuration and opens logging before runtime creation;
- `service.starting`, `service.started`, `service.stopping`, and `service.stopped` order and fields;
- later configuration failure records `service.configuration.failed` and rethrows the same safe public exception;
- runtime start/close failures record component-specific errors while cleanup ordering and suppressed failures remain unchanged;
- logging session closes once after the final service event.

```kotlin
@Test
fun `product command never opens backend logging`() {
    var loggingStarts = 0
    val result = rootCommand(
        runService = { loggingStarts++ },
        productDependencies = productDependencies(),
    ).test("--version")
    assertEquals(0, result.statusCode)
    assertEquals(0, loggingStarts)
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.bootstrap.ServiceLoggingBootstrapTest' --tests 'com.mindtable.bitbuckethelper.bootstrap.ServiceRuntimeLifecycleTest'
```

Expected: new tests fail because bootstrap seams and lifecycle events are absent.

- [ ] **Step 3: Implement service-only bootstrap and explicit identity**

Add:

```kotlin
internal data class ServiceBootstrapSeams(
    val config: Config,
    val serviceInstanceIdSource: () -> String,
    val openLogging: (LoggingConfiguration, String) -> ServiceLoggingSession,
    val createRuntime: (ServiceConfiguration, String, BackendEventRecorder, OperationalEventRecorder) -> ServiceRuntime,
)
```

Keep the public `runConfiguredService(environment)` entrypoint and delegate to an internal overload accepting these seams. Its order is:

1. load logging settings;
2. allocate `svc_<uuid>`;
3. open logging;
4. record `service.starting`;
5. load full service configuration;
6. compose/start runtime;
7. record `service.started` with resolved browser port;
8. await shutdown;
9. record stopping, close all runtime resources, record stopped;
10. close Log4j last.

Change `ServiceRuntime.create` to accept the already allocated service instance and both recorders. Retain the existing public `create(configuration, clock)` overload for compatibility; it delegates with a generated service ID and both no-op recorders. Add an internal production overload `create(configuration, serviceInstanceId, backendRecorder, operationalRecorder, clock, lifecycleProbe, schedulerClock)`. Production calls only the internal overload and must not allocate a second identity. Attach component names to startup/cleanup actions so `ServiceStartFailed` and `ServiceStopFailed` identify `persistence`, `bitbucket_gateway`, `scheduler`, `http_servers`, or `service_scope` without logging paths or objects.

`Main.kt` keeps fixed `System.err` lines only for pre-logging/final process fallback. Do not pass failures or messages to `println`; active logging occurs inside `runConfiguredService` before rethrow.

- [ ] **Step 4: Run bootstrap, lifecycle, and CLI regression tests**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.bootstrap.*' --tests 'com.mindtable.bitbuckethelper.cli.*'
```

Expected: PASS; product command captures remain unchanged and service lifecycle ordering remains reverse-cleanup-safe.

- [ ] **Step 5: Self-review and commit**

Run `git diff --check`, inspect every `System.out`/`System.err` occurrence under `bootstrap` and confirm only the fixed `Main.kt` fallback remains. Commit:

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Commands.kt src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Main.kt src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/RootCommandTest.kt src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntimeLifecycleTest.kt src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceLoggingBootstrapTest.kt
git commit -m "feat: log backend service lifecycle"
```

---

### Task 4: Correlated HTTP Request and Outcome Logging

**Files:**
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Observability.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Module.kt:16-56`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Errors.kt:105-239`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Envelopes.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ReadRoutes.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ActionItemRoutes.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/RefreshRunRoutes.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ConfigurationRoutes.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/HealthRoutes.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BrowserSecurity.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/LocalApiServers.kt:34-169`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1ModuleTest.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiTransportAcceptanceTest.kt`
- Modify: route test files under `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/`

**Interfaces:**
- Consumes: `BackendEventRecorder`, `MonotonicTimeSource`, existing server request IDs, and fixed backend HTTP events.
- Produces: one safe terminal event for every `/api/v1` call on both transports, with request-to-refresh correlation for later tasks.

- [ ] **Step 1: Write failing API observer tests**

Use a recording `BackendEventRecorder` and deterministic nanos. Cover browser and Unix success, mutation, invalid JSON, forbidden browser request, missing route with a private sentinel path/query, method not allowed, unsupported media type, and unexpected failure whose message contains token/body/path/ANSI sentinels. Assert exactly one event, correct request ID/status/level/operation/outcome/duration, and no raw URI/query/header/body.

```kotlin
@Test
fun `successful polling emits one debug event with the response request id`() = testApplication {
    val events = mutableListOf<BackendLogEvent>()
    application { installApiV1(TransportKind.BROWSER, BackendEventRecorder(events::add), fakeTime) { testRoutes() } }
    val response = client.get("/api/v1/test/success?private=never-log")
    val requestId = response.body<JsonObject>().getValue("requestId").jsonPrimitive.content
    val event = events.single() as BackendLogEvent.HttpRequestCompleted
    assertEquals(requestId, event.requestId)
    assertEquals("test_success", event.operation)
    assertFalse(event.toString().contains("private"))
}
```

- [ ] **Step 2: Run the HTTP slice and verify RED**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.adapter.inbound.http.*'
```

Expected: new event assertions fail because no API observer is installed.

- [ ] **Step 3: Implement fixed operation/outcome attributes**

Define `ApiOperation` constants for `browser_session`, `health`, `get_dashboard`, `list_pull_requests`, `get_pull_request`, `get_inbox`, `get_synchronization`, `get_live_activity_content`, `acknowledge_action_item`, `start_refresh_run`, `get_refresh_run`, `get_workspace_configuration`, `configure_workspace`, `add_repository`, `remove_repository`, and `route_not_found`. Mark read/poll versus mutation in the enum.

Define `ApiOutcome` constants for all existing wire result kinds: `browser_session`, `health_snapshot`, `snapshot_changed`, `snapshot_unchanged`, `pull_requests_available`, `pull_request_found`, `pull_request_not_found`, `inbox_available`, `synchronization_available`, `content_available`, `stale_activity_version`, `newer_activity_observed`, `content_unavailable`, `action_item_not_found`, `acknowledged`, `already_acknowledged`, `acknowledgment_rejected`, `workspace_not_configured`, `no_repositories_configured`, `refresh_run_registered`, `refresh_run_in_progress`, `refresh_run_completed`, `refresh_run_unavailable`, `workspace_configuration_available`, `workspace_configured`, `workspace_already_configured`, `workspace_identity_mismatch`, `workspace_not_found`, `workspace_resolution_unavailable`, `repository_added`, `repository_already_configured`, `repository_not_found`, `repository_resolution_unavailable`, `repository_removed`, and `repository_not_configured`.

At the first line of each route, assign its fixed operation. Before `respond`, assign an outcome with an exhaustive `when` over the application result and attach only validated IDs (`refresh_run_id`, `repository_id`, `pull_request_id`, or `action_item_id`) through typed optional attributes. Never inspect class simple names or serialize a result for logging.

- [ ] **Step 4: Install exactly-one terminal observation**

Wrap API processing in `ApplicationCallPipeline.Monitoring`, record start nanos, and in `finally` emit one event after StatusPages has produced the response. `ApiV1Errors` sets a fixed request-error code or the unexpected throwable attribute; the observer sanitizes through `BackendEventRecorder`. Missing/unknown paths use `route_not_found` and never record `request.path()`.

Change `installApiV1` and `LocalApiServers.start` to accept the recorder/time source and pass the same recorder to browser and Unix servers. Keep default no-op parameters for focused transport tests that do not assert logs.

- [ ] **Step 5: Run HTTP and privacy regressions**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.adapter.inbound.http.*' --tests 'com.mindtable.bitbuckethelper.V1SecurityAndPrivacyTest'
```

Expected: PASS; every response contract/status remains unchanged and no captured diagnostic contains private sentinels.

- [ ] **Step 6: Self-review and commit**

Run `git diff --check` and search HTTP production code for logger calls, raw `uri`, raw `path`, `queryParameters.toString`, headers, and bodies. Only the fixed typed observer may record. Commit:

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http
git commit -m "feat: add correlated API request logging"
```

---

### Task 5: Scheduler, Persistence, and Health Failure Logging

**Files:**
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/SuspendingUseCaseJob.kt:11-52`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/ApplicationUseCaseJobFactory.kt:8-31`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/QuartzApplicationScheduler.kt:38-238`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqApplicationPersistence.kt:12-31`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/GetHealthSnapshotService.kt:12-55`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/QuartzApplicationSchedulerTest.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqApplicationPersistenceTest.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/application/service/GetHealthSnapshotServiceTest.kt`

**Interfaces:**
- Consumes: backend/application recorders and deterministic time source from Task 2; runtime injection from Task 3.
- Produces: scheduler execution correlation, message-free failure details before Quartz sanitization, transaction failure evidence, and health-probe evidence.

- [ ] **Step 1: Replace stderr-capture expectations with failing event expectations**

Update scheduler tests to record `scheduler.job.started`, completed, timed-out, failed, and interrupted events. Assert unique `scheduler_execution_id`, fixed job key, deterministic duration, timeout WARN, unexpected failure ERROR, no raw message, restored interrupt flag, unchanged non-refiring `JobExecutionException`, and unchanged `Error` propagation.

Add persistence test failure from inside a transaction and assert `persistence.transaction.failed` before the identical throwable is rethrown, without SQL/bind sentinels. Add health test asserting `OperationalEvent.HealthProbeFailed` while the same unhealthy safe snapshot is returned and cancellation is not recorded.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.adapter.inbound.scheduler.QuartzApplicationSchedulerTest' --tests 'com.mindtable.bitbuckethelper.adapter.outbound.persistence.JooqApplicationPersistenceTest' --tests 'com.mindtable.bitbuckethelper.application.service.GetHealthSnapshotServiceTest'
```

Expected: scheduler tests still see `System.err`; recorder assertions fail.

- [ ] **Step 3: Implement scheduler execution/lifecycle events**

Extend `SuspendingUseCaseJob` with `useCaseKey`, `executionIdSource: () -> String`, `recorder`, and `timeSource`. Record start at DEBUG; completion at INFO; timeout/interruption at WARN with no original throwable; ordinary unexpected `Exception` at ERROR with the original passed only inside `BackendLogEvent.SchedulerJobFailed` for immediate sanitizer conversion. Remove `System.err.println` completely. For an `Error`, record one sanitized `SchedulerJobFailed` and rethrow the identical `Error` object without translation or stringification.

Pass the fixed key through `ApplicationUseCaseJobFactory`. Record scheduler start/stop and registration/start/shutdown failures inside `QuartzApplicationScheduler` without changing terminal health codes or retrying cleanup behavior.

- [ ] **Step 4: Implement persistence and health events**

Give `JooqApplicationPersistence.open(path, recorder = BackendEventRecorder.NONE)` a recorder. In the outer transaction catch, attempt rollback, record one `PersistenceTransactionFailed(operation = "transaction", failure)`, then rethrow the original with existing rollback/suppressed behavior. Do not record successful SQL or data.

Give `GetHealthSnapshotService` an `OperationalEventRecorder` defaulting to NONE. In the non-cancellation exception branch, record `HealthProbeFailed(component, failure)` before returning the unchanged `probe_failed` snapshot. Production runtime injects the real operational recorder.

- [ ] **Step 5: Run focused and integration regressions**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.adapter.inbound.scheduler.*' --tests 'com.mindtable.bitbuckethelper.adapter.outbound.persistence.*' --tests 'com.mindtable.bitbuckethelper.application.service.GetHealthSnapshotServiceTest' --tests 'com.mindtable.bitbuckethelper.NotificationIntegrationTest'
```

Expected: PASS with no production `System.err` in scheduler code.

- [ ] **Step 6: Self-review and commit**

Run `git diff --check`; search for SQL text passed to event constructors and verify cancellation branches do not record as unexpected failures. Commit:

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqApplicationPersistence.kt src/main/kotlin/com/mindtable/bitbuckethelper/application/service/GetHealthSnapshotService.kt src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqApplicationPersistenceTest.kt src/test/kotlin/com/mindtable/bitbuckethelper/application/service/GetHealthSnapshotServiceTest.kt
git commit -m "feat: log scheduler and component failures"
```

---

### Task 6: Bitbucket and Notification-Process Boundary Logging

**Files:**
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketGateway.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketAccountGateway.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification/DesktopNotificationsProcessAdapter.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketGatewayTest.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketAccountGatewayTest.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification/DesktopNotificationsProcessAdapterTest.kt`

**Interfaces:**
- Consumes: backend recorder/time source and production composition.
- Produces: safe fixed-operation upstream/provider outcomes, including typed categories and durations without wire/process data.

- [ ] **Step 1: Write failing adapter observation tests**

For Bitbucket, cover success, 401, 403, 404, 429/retry time, 5xx, timeout, network error, malformed JSON containing private body content, unsafe pagination, cancellation, and an unexpected exception containing URL/header/body sentinels. Assert fixed operation, repository ID when available, status when available, safe category/retryability/duration, and absence of every wire sentinel.

For the notification process, cover accepted, provider-declared failure, process-not-started, timeout, malformed response, unexpected exit, signal/ambiguous failure, and cancellation. Assert category/duration only; reject delivery key, title, body, sound, open URL, executable path, argv, stdout, and stderr.

- [ ] **Step 2: Run adapter tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.*' --tests 'com.mindtable.bitbuckethelper.adapter.outbound.notification.*'
```

Expected: event assertions fail because adapters do not accept recorders.

- [ ] **Step 3: Instrument Bitbucket at existing mapping boundaries**

Add `recorder: BackendEventRecorder = NONE` and `timeSource: MonotonicTimeSource = SYSTEM` to gateway factory seams. Use a fixed `BitbucketOperation` enum for `current_user`, `workspace`, `repository`, `pull_requests`, `pull_request_detail`, `default_reviewers`, `build_statuses`, `tasks`, `activity`, `file_conflicts`, `comment`, and `live_activity_content`.

Wrap each public gateway operation once. Success records DEBUG. Typed HTTP/timeout/network/malformed/unsafe-pagination results record WARN with category; cancellation rethrows without an unexpected event. Only an exception outside known mappings carries a throwable into `BackendLogEvent.BitbucketRequestFailed` for sanitization. Never enable Ktor client `Logging` and never include generated request/response objects.

- [ ] **Step 4: Instrument provider process classification**

Add recorder/time source to `DesktopNotificationsProcessAdapter`. Emit `notification.provider.completed` at DEBUG for accepted and `notification.provider.failed` at WARN for safe category/ambiguity/duration. This adapter does not know intent/attempt IDs; Task 7 emits those application correlations. Do not include `NotificationRequest` fields or captured process bytes.

Inject both instrumented adapters in `ServiceRuntime`.

- [ ] **Step 5: Run adapter and privacy regressions**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.*' --tests 'com.mindtable.bitbuckethelper.adapter.outbound.notification.*' --tests 'com.mindtable.bitbuckethelper.V1SecurityAndPrivacyTest'
```

Expected: PASS; all current typed results remain equal and diagnostics exclude sentinels.

- [ ] **Step 6: Self-review and commit**

Run `git diff --check`; search changed files for logger/body/header/URL/request/response/process-output rendering. Commit:

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification/DesktopNotificationsProcessAdapter.kt src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification/DesktopNotificationsProcessAdapterTest.kt
git commit -m "feat: log outbound integration outcomes"
```

---

### Task 7: Refresh and Notification Correlation Events

**Files:**
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshRunServices.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RepositoryRefreshCoordinator.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshAllRepositoriesService.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/DispatchNotificationsService.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshRunServicesTest.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/application/service/RepositoryRefreshCoordinatorTest.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshRepositoryActionItemsTest.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/application/service/DispatchNotificationsServiceTest.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/NotificationIntegrationTest.kt`

**Interfaces:**
- Consumes: `OperationalEventRecorder` and exact sealed events from Task 2.
- Produces: request-log-discoverable refresh run/repository outcomes and notification intent/attempt/retry outcomes across independent coroutines.

- [ ] **Step 1: Write failing application event tests**

Use a recording framework-free `OperationalEventRecorder`. Assert:

- refresh registration counts started/joined/deferred/not-configured dispositions;
- each `refreshRunId`/`repositoryId` monitor records exactly one success, partial, failed, deferred, not-configured, cancellation removal, or unexpected terminal result;
- scheduled refreshes record repository results with `refreshRunId = null`;
- joined flights produce one event per monitoring run/repository pair;
- notification accepted, retry scheduled, exhausted, and category/ambiguity fields include intent/attempt IDs and attempt number;
- cancellation preserves identity and a failed claim release emits `NotificationCleanupFailed` without the cleanup message.

```kotlin
@Test
fun `registered run correlates every monitored repository terminal outcome`() = runTest {
    val events = mutableListOf<OperationalEvent>()
    val services = refreshServices(recorder = OperationalEventRecorder(events::add))
    val registered = services.start(StartRefreshRunCommand(RefreshTarget.AllConfiguredRepositories))
        as StartRefreshRunResult.RefreshRunRegistered
    advanceUntilIdle()
    assertEquals(
        registered.refreshRun.repositories.map { it.repositoryId }.toSet(),
        events.filterIsInstance<OperationalEvent.RefreshRepositoryFinished>()
            .filter { it.refreshRunId == registered.refreshRun.id }
            .map { it.repositoryId }.toSet(),
    )
}
```

- [ ] **Step 2: Run application tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.application.service.RefreshRunServicesTest' --tests 'com.mindtable.bitbuckethelper.application.service.RepositoryRefreshCoordinatorTest' --tests 'com.mindtable.bitbuckethelper.application.service.DispatchNotificationsServiceTest'
```

Expected: constructors do not accept a recorder and no events exist.

- [ ] **Step 3: Emit refresh events without thread-local context**

Inject `OperationalEventRecorder = NONE` and `MonotonicTimeSource = SYSTEM` into the three refresh services. `RefreshRunServices.start` emits `RefreshRunRegistered` after the registry creates the run. `monitor` captures run/repository IDs as ordinary values before launching, measures monotonic duration, and records exactly one terminal event in success/failure branches. Cancellation removes the repository and rethrows without converting cancellation into an unexpected failure event.

`RepositoryRefreshCoordinator` records only an unexpected flight failure that would otherwise complete exceptionally; do not duplicate known result events. `RefreshAllRepositoriesService` emits terminal events for scheduled known results with no run ID and records repository ID before rethrowing any unexpected child failure to Quartz.

- [ ] **Step 4: Emit notification attempt/retry events**

Inject the recorder/time source into `DispatchNotificationsService`. After the attempt ID and retry decision exist and persistence completion succeeds, emit one `NotificationAttemptFinished`. Map accepted/retry/exhausted exhaustively to the fixed enums. On cancellation release failure, emit `NotificationCleanupFailed(id, failure)` before attaching the existing sanitized suppressed cleanup marker and rethrowing the original cancellation. Do not include `claimed.request`, worker UUID, lease owner, or delivery key.

Wire the real recorder through `ServiceRuntime`.

- [ ] **Step 5: Run application and notification integration tests**

Run:

```bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.application.service.*' --tests 'com.mindtable.bitbuckethelper.NotificationIntegrationTest'
```

Expected: PASS with unchanged business models, retry times, registry states, and cancellation behavior.

- [ ] **Step 6: Self-review and commit**

Run `git diff --check`; verify no application file imports a logging framework and every independent launch carries IDs explicitly. Commit:

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshRunServices.kt src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RepositoryRefreshCoordinator.kt src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshAllRepositoriesService.kt src/main/kotlin/com/mindtable/bitbuckethelper/application/service/DispatchNotificationsService.kt src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt src/test/kotlin/com/mindtable/bitbuckethelper/application/service src/test/kotlin/com/mindtable/bitbuckethelper/NotificationIntegrationTest.kt
git commit -m "feat: correlate refresh and notification logs"
```

---

### Task 8: End-to-End Privacy, Operations Documentation, and Permanent Policy

**Files:**
- Create: `src/test/kotlin/com/mindtable/bitbuckethelper/BackendLoggingAcceptanceTest.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/V1SecurityAndPrivacyTest.kt`
- Modify: `README.md`
- Modify: `docs/operations/manual-service-run.md`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: complete logging composition from Tasks 1-7.
- Produces: executable acceptance evidence, operator guidance, and durable contribution requirements.

- [ ] **Step 1: Write the failing real-composition acceptance test**

Launch the fat JAR `service run` with secure temporary database/socket/log directories, fake Bitbucket and notification providers, and DEBUG default. Drive health, workspace configuration, repository addition, refresh registration/polling, a rejected request, a partial/failed upstream call, and shutdown. Parse JSON Lines and assert:

- DEBUG events appear in both captured terminal and file;
- service/request/refresh run/repository/scheduler/notification correlations are searchable;
- current/archived modes and directory mode are owner-only;
- final `service.stopped` is flushed;
- every JSON line parses and numeric/boolean fields remain typed;
- tokens, Authorization, cookies, private query/path/body markers, upstream bodies/headers, activity Markdown, notification content, provider output, SQL/bind values, absolute path sentinels, exception messages, newline/ANSI/control sentinels are absent from both outputs.

In a second fresh process run a product command against a missing Unix socket with a dedicated `BITBUCKET_HELPER_LOG_DIRECTORY`; assert the existing CLI document/exit and that the directory/file do not exist.

- [ ] **Step 2: Run the acceptance test and verify RED if any requirement is missing**

Run:

```bash
./gradlew buildFatJar test --tests 'com.mindtable.bitbuckethelper.BackendLoggingAcceptanceTest' --tests 'com.mindtable.bitbuckethelper.V1SecurityAndPrivacyTest'
```

Expected before final fixes: at least one missing correlation/privacy/flush assertion fails. Fix only the owning Task 1-7 implementation, add a focused regression beside that component, and rerun until PASS.

- [ ] **Step 3: Update README and manual runbook**

Add the two logging settings to the README configuration table and state backend-only initialization, default DEBUG in terminal and JSON, active path `var/log/bitbucket-helper.jsonl`, 10 MiB/UTC-day rotation, gzip, 14-day retention, 200 MiB cap, and privacy boundary.

Add runbook commands that never expose values:

```bash
jq -c 'select(.request_id == "req_REPLACE_WITH_ID")' var/log/bitbucket-helper.jsonl
jq -c 'select(.refresh_run_id == "rr_REPLACE_WITH_ID")' var/log/bitbucket-helper.jsonl
jq -c 'select(.repository_id == "repo_REPLACE_WITH_ID")' var/log/bitbucket-helper.jsonl
jq -c 'select(.scheduler_execution_id == "se_REPLACE_WITH_ID")' var/log/bitbucket-helper.jsonl
jq -c 'select(.notification_intent_id == "ni_REPLACE_WITH_ID")' var/log/bitbucket-helper.jsonl
```

Explain that exception messages/payloads are intentionally absent; class and stack locations plus safe categories remain.

- [ ] **Step 4: Add the permanent `AGENTS.md` section exactly**

Append `## Logging and diagnostics` containing all approved policy points: Log4j2; backend only; both outputs; default DEBUG; stable events and required correlations at changed boundaries; explicit async propagation; privacy allowlist/denylist; domain logging-free/application typed port; prohibition on backend `println`, `System.out`, `System.err`, `printStackTrace`, generic access/wire logs, and discarded causes except the fixed pre-initialization process fallback; both-destination success/failure/privacy tests; and logging severity independent from response-body/HTTP semantics.

- [ ] **Step 5: Run complete verification**

Populate only the pinned dependency cache if Task 1 required a network resolution, then prove the repository works offline:

```bash
./gradlew --offline clean check verifyApiV1Generated buildFatJar
npm --prefix web run check
git diff --check
git status --short
```

Expected: all commands PASS. `git status --short` lists only the intended Task 8 files before commit. Inspect test reports for hidden failures and inspect a generated sample JSON log for schema and privacy.

- [ ] **Step 6: Commit the acceptance evidence and policy**

```bash
git add src/test/kotlin/com/mindtable/bitbuckethelper/BackendLoggingAcceptanceTest.kt src/test/kotlin/com/mindtable/bitbuckethelper/V1SecurityAndPrivacyTest.kt README.md docs/operations/manual-service-run.md AGENTS.md
git commit -m "docs: require diagnosable backend logging"
```

Record the final commit range and verification outputs for the completion report. Do not push, open a PR, or change branches unless the user separately asks.
