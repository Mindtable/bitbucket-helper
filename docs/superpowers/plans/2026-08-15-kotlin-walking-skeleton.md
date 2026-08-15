# Kotlin Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one runnable Kotlin/JVM service whose immediate Quartz job reads the authenticated Bitbucket account through an OpenAPI-generated client, persists a sanitized connection snapshot in Liquibase-managed SQLite through jOOQ, and exposes it through Ktor.

**Architecture:** Keep the one-module codebase on the dependency path `domain <- application <- adapters <- bootstrap`; this operational slice has no domain aggregate, so its stable models and ports live in `application`. Build-time tasks reduce the committed canonical Bitbucket OpenAPI snapshot to exactly `GET /user`, generate the client, migrate a fresh temporary SQLite database, and generate jOOQ sources, all below `build/`. Bootstrap validates secrets before any database side effect, composes the adapters, and owns reverse-order shutdown.

**Tech Stack:** Gradle 9.6.1 Kotlin DSL, JDK 25, Kotlin 2.4.10, Ktor 3.5.1 with CIO and `kotlinx.serialization`, Clikt 5.1.0, Quartz 2.5.2, Liquibase 5.0.3 XML changelogs, SQLite JDBC 3.53.1.0, jOOQ 3.21.6, OpenAPI Generator 7.24.0, JUnit 6.1.3, and ArchUnit 1.4.2.

## Global Constraints

- Implement only the approved walking skeleton in `docs/superpowers/specs/2026-08-15-kotlin-walking-skeleton-design.md`; do not implement pull-request behavior, the Vue UI, notifications, installation, or the full product CLI.
- Use one Gradle project and one Kotlin/JVM module with group and base package `com.mindtable.bitbuckethelper`.
- Pin exactly Gradle `9.6.1`, JDK `25`, Kotlin `2.4.10`, Ktor `3.5.1`, `kotlinx.serialization-json` `1.11.0`, coroutines `1.11.0`, Clikt `5.1.0`, Quartz `2.5.2`, Liquibase `5.0.3`, jOOQ `3.21.6`, Xerial SQLite JDBC `3.53.1.0`, OpenAPI Generator `7.24.0`, ArchUnit `1.4.2`, and JUnit `6.1.3` in `gradle/libs.versions.toml`.
- If those exact pins do not resolve and compile together, stop and report the incompatibility; do not silently change a version.
- Follow strict RED-GREEN-REFACTOR for behavior. Initial Gradle wrapper/build wiring, Liquibase/jOOQ generation wiring, OpenAPI snapshot/generation wiring, and generated outputs are non-behavioral configuration exceptions only after the user explicitly approves those exceptions.
- Preserve the untracked `source/` prototype byte-for-byte and never stage it. Do not stage the user's root `AGENTS.md` or unrelated `docs/project-backlog.md` changes.
- Keep the dependency direction `domain <- application <- adapters <- bootstrap`; application code must not import Ktor, Clikt, Quartz, Liquibase, jOOQ, SQLite, or either generated package.
- Normal `compile`, `test`, `check`, and packaging tasks must use the committed OpenAPI snapshot and must not fetch from or call Bitbucket Cloud.
- Generate Bitbucket and jOOQ Kotlin only below `build/generated/sources`; never commit generated Kotlin or the code-generation SQLite database.
- Read credentials only from `BITBUCKET_USERNAME` and `BITBUCKET_APP_PASSWORD`. The username is the Atlassian account email; the legacy-named app-password variable contains a current Bitbucket API token with minimum scope `read:user:bitbucket`.
- Reject missing or blank credentials before creating a database directory or file. Never print, log, persist, return, or include either value in an exception.
- Every migration is Liquibase XML. The master is `src/main/resources/db/changelog/db.changelog-master.xml`; versioned migrations are `src/main/resources/db/migration/V%04d__<snake_case_description>.xml` and match `^V[0-9]{4}__[a-z0-9]+(?:_[a-z0-9]+)*\.xml$`.
- Reject migration version `0000` and duplicate numeric prefixes; permit gaps. Each changeSet has a unique ID, practical rollback, and becomes immutable after application.
- A valid `GET /api/v1/bitbucket/status` query returns `200 OK` for pending, healthy, and failed connection states. Use `4xx` only for malformed requests and `500` for unexpected server failures; never encode business state as `202`, `409`, or `503`.
- Quartz uses `RAMJobStore`, fires immediately, repeats every `PT15M` by default, skips catch-up bursts, prevents concurrent execution of the same job, awaits the suspending use case, and never launches detached work.
- The automated suite uses local fakes and temporary files only. It must not require live credentials or contact Bitbucket Cloud.
- Keep Testcontainers as a separate deferred integration-suite item. Do not add Testcontainers dependencies, source sets, Gradle tasks, or tests in this plan.
- Every task ends with focused verification, self-review, and a commit that stages only that task's files.

---

## File and Responsibility Map

### Build and generated-source inputs

- `settings.gradle.kts` — repository name and dependency repositories.
- `build.gradle.kts` — plugins, source sets, generation graph, migration validation, tests, application entrypoint, and fat-JAR configuration.
- `gradle/libs.versions.toml` — the complete pinned dependency baseline.
- `gradle/wrapper/*`, `gradlew`, `gradlew.bat` — Gradle 9.6.1 wrapper.
- `specs/bitbucket-cloud/openapi.json` — byte-for-byte committed canonical Atlassian snapshot.
- `specs/bitbucket-cloud/README.md` — source URL, retrieval date, SHA-256, generator version, and update/review procedure.

### Application boundary

- `src/main/kotlin/com/mindtable/bitbuckethelper/application/model/BitbucketConnectionModels.kt` — stable account, failure, and snapshot models.
- `src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/RefreshBitbucketConnection.kt` — refresh command.
- `src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/GetBitbucketConnectionStatus.kt` — current-snapshot query.
- `src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/BitbucketAccountGateway.kt` — generated-client-free account port.
- `src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/BitbucketConnectionRepository.kt` — persistence port.
- `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshBitbucketConnectionService.kt` — one-attempt orchestration.
- `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/GetBitbucketConnectionStatusService.kt` — query delegation.

### Adapters

- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/SqliteDatabase.kt` — SQLite configuration and Liquibase runtime migration.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqBitbucketConnectionRepository.kt` — atomic singleton persistence and mapping.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketAccountGateway.kt` — generated API invocation, Basic auth, mapping, sanitized failures, and HTTP-client ownership.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BitbucketStatusRoutes.kt` — Ktor serialization, response DTOs, route, and unexpected-error mapping.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/RefreshBitbucketConnectionJob.kt` — synchronous Quartz-to-coroutine bridge.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/UseCaseJobFactory.kt` — constructor injection for Quartz jobs.
- `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/QuartzRefreshScheduler.kt` — RAM scheduler, job, trigger, lifecycle, and misfire policy.

### Bootstrap

- `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceConfiguration.kt` — typed, validated non-secret and secret configuration.
- `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt` — composition, ordered startup, reverse cleanup, and test seam.
- `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Commands.kt` — Clikt root, `service`, and `service run` commands.
- `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Main.kt` — one process entrypoint and sanitized fatal diagnostic.
- `src/main/resources/application.conf` — developer-local non-secret defaults.

### Database

- `src/main/resources/db/changelog/db.changelog-master.xml` — XML master with `.xml`-filtered `includeAll`.
- `src/main/resources/db/migration/V0001__create_bitbucket_connection_snapshot.xml` — singleton snapshot table and rollback.
- `src/main/resources/db/migration/AGENTS.md` — durable migration authoring rules.

### Tests

- Mirror each production package under `src/test/kotlin`.
- Put the canonical successful current-user response in `src/test/resources/bitbucket/current-user-success.json`.
- `ArchitectureTest.kt` enforces dependency direction and generated-type containment.
- `WalkingSkeletonEndToEndTest.kt` crosses Quartz, generated client, application service, jOOQ/SQLite, and Ktor.
- `MissingCredentialsProcessTest.kt` launches the fat JAR and proves pre-database failure.

---

### Task 1: Reproducible Gradle Foundation and Clikt Command Shape

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Modify: `.gitignore`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Commands.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Main.kt`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/RootCommandTest.kt`

**Interfaces:**
- Consumes: no earlier implementation task.
- Produces: `fun rootCommand(runService: () -> Unit): CliktCommand`, `fun main(args: Array<String>)`, Gradle tasks `test`, `check`, and `buildFatJar`, and a JDK 25 Kotlin source set used by every later task.

- [ ] **Step 1: Create the approved build-tooling exception after confirming the user approved it**

Create the wrapper with Gradle `9.6.1`, set `rootProject.name = "bitbucket-helper"`, and centralize every version from Global Constraints in `gradle/libs.versions.toml`. Configure Maven Central, Kotlin/JVM and serialization, `application`, Ktor, OpenAPI Generator, and official jOOQ plugins. Configure the Java toolchain and Kotlin JVM toolchain to 25, JUnit Platform, main class `com.mindtable.bitbuckethelper.bootstrap.MainKt`, and Ktor fat-JAR task. Do not configure the generation tasks yet.

Use these catalog coordinates and no unlisted version aliases:

```toml
[versions]
kotlin = "2.4.10"
ktor = "3.5.1"
kotlinx-serialization = "1.11.0"
kotlinx-coroutines = "1.11.0"
clikt = "5.1.0"
quartz = "2.5.2"
liquibase = "5.0.3"
jooq = "3.21.6"
sqlite = "3.53.1.0"
openapi-generator = "7.24.0"
archunit = "1.4.2"
junit = "6.1.3"

[libraries]
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }
clikt = { module = "com.github.ajalt.clikt:clikt", version.ref = "clikt" }
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-cio = { module = "io.ktor:ktor-server-cio", version.ref = "ktor" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation", version.ref = "ktor" }
ktor-server-status-pages = { module = "io.ktor:ktor-server-status-pages", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-jackson = { module = "io.ktor:ktor-serialization-jackson", version.ref = "ktor" }
ktor-server-test-host = { module = "io.ktor:ktor-server-test-host", version.ref = "ktor" }
quartz = { module = "org.quartz-scheduler:quartz", version.ref = "quartz" }
liquibase-core = { module = "org.liquibase:liquibase-core", version.ref = "liquibase" }
jooq = { module = "org.jooq:jooq", version.ref = "jooq" }
sqlite-jdbc = { module = "org.xerial:sqlite-jdbc", version.ref = "sqlite" }
archunit-junit5 = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version.ref = "junit" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ktor = { id = "io.ktor.plugin", version.ref = "ktor" }
openapi-generator = { id = "org.openapi.generator", version.ref = "openapi-generator" }
jooq-codegen = { id = "org.jooq.jooq-codegen-gradle", version.ref = "jooq" }
```

Declare production dependencies for Clikt, coroutines, Ktor server/client, both Ktor serializers, Quartz, Liquibase, jOOQ, and SQLite. Clikt's main module includes its command-test extension, and Ktor 3.5.1 supports HOCON configuration without a separate config-HOCON artifact. Declare test dependencies for coroutine testing, Ktor server test host, JUnit Jupiter, ArchUnit, and the JUnit platform launcher. Do not add a logging plugin that prints HTTP headers or bodies.

The foundation portion of `build.gradle.kts` must include:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.jooq.codegen)
    application
}

group = "com.mindtable.bitbuckethelper"
version = "0.1.0"

kotlin { jvmToolchain(25) }

application {
    mainClass.set("com.mindtable.bitbuckethelper.bootstrap.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
```

Add exactly these ignore entries without removing existing user entries:

```gitignore
.gradle/
build/
var/
```

Run: `./gradlew --version`

Expected: Gradle `9.6.1` and JVM `25`; if either is unavailable, stop with the exact output.

- [ ] **Step 2: Write the failing command-shape tests**

```kotlin
package com.mindtable.bitbuckethelper.bootstrap

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootCommandTest {
    @Test
    fun `help advertises service run without starting the service`() {
        var starts = 0

        val result = rootCommand { starts += 1 }.test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("service"))
        assertEquals(0, starts)
    }

    @Test
    fun `service run invokes the injected bootstrap exactly once`() {
        var starts = 0

        val result = rootCommand { starts += 1 }.test("service run")

        assertEquals(0, result.statusCode)
        assertEquals(1, starts)
    }

    @Test
    fun `version is stable`() {
        val result = rootCommand { }.test("--version")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("0.1.0"))
    }
}
```

- [ ] **Step 3: Run the focused tests to verify RED**

Run: `./gradlew test --tests '*RootCommandTest'`

Expected: compilation fails because `rootCommand` does not exist.

- [ ] **Step 4: Implement the minimal Clikt command tree**

```kotlin
package com.mindtable.bitbuckethelper.bootstrap

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption

private const val APPLICATION_VERSION = "0.1.0"

private class BitbucketHelperCommand : CliktCommand(name = "bitbucket-helper") {
    init { versionOption(APPLICATION_VERSION) }
    override fun run() = Unit
}

private class ServiceCommand : CliktCommand(name = "service") {
    override fun run() = Unit
}

private class ServiceRunCommand(
    private val runService: () -> Unit,
) : CliktCommand(name = "run") {
    override fun run() = runService()
}

fun rootCommand(runService: () -> Unit): CliktCommand =
    BitbucketHelperCommand().subcommands(
        ServiceCommand().subcommands(ServiceRunCommand(runService)),
    )
```

Use this temporary entrypoint until Task 10 supplies production composition:

```kotlin
package com.mindtable.bitbuckethelper.bootstrap

import com.github.ajalt.clikt.core.main

fun main(args: Array<String>) {
    rootCommand { error("service runtime has not been composed") }.main(args)
}
```

- [ ] **Step 5: Verify GREEN and dependency resolution**

Run: `./gradlew test --tests '*RootCommandTest'`

Expected: all three tests pass.

Run: `./gradlew dependencies --configuration runtimeClasspath`

Expected: the exact pinned versions resolve; no dynamic `+`, `latest`, or snapshot version appears.

- [ ] **Step 6: Self-review and commit**

Check: `git diff --check`

Check: `git status --short` contains only Task 1 files plus pre-existing protected user changes.

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle gradlew gradlew.bat src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Commands.kt src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Main.kt src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/RootCommandTest.kt
git commit -m "build: establish Kotlin Gradle foundation"
```

---

### Task 2: Typed Configuration and Credential-First Failure

**Files:**
- Create: `src/main/resources/application.conf`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceConfiguration.kt`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceConfigurationTest.kt`

**Interfaces:**
- Consumes: JDK 25 and Ktor/HOCON dependencies from Task 1.
- Produces: `class BitbucketCredentials`, `data class ServiceConfiguration`, `class StartupConfigurationException`, and `object ServiceConfigurationLoader { fun load(config: Config, environment: Map<String, String>): ServiceConfiguration }` for Task 10.

- [ ] **Step 1: Write failing tests for credentials, redaction, defaults, and overrides**

```kotlin
package com.mindtable.bitbuckethelper.bootstrap

import com.typesafe.config.ConfigFactory
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServiceConfigurationTest {
    private val defaults = ConfigFactory.parseResources("application.conf").resolve()
    private val credentials = mapOf(
        "BITBUCKET_USERNAME" to "person@example.com",
        "BITBUCKET_APP_PASSWORD" to "sentinel-api-token",
    )

    @Test
    fun `missing username is named without exposing the token`() {
        val error = assertThrows(StartupConfigurationException::class.java) {
            ServiceConfigurationLoader.load(defaults, credentials - "BITBUCKET_USERNAME")
        }
        assertTrue(error.message!!.contains("BITBUCKET_USERNAME"))
        assertFalse(error.message!!.contains("sentinel-api-token"))
    }

    @Test
    fun `blank legacy token variable is rejected without exposing its value`() {
        val error = assertThrows(StartupConfigurationException::class.java) {
            ServiceConfigurationLoader.load(
                defaults,
                credentials + ("BITBUCKET_APP_PASSWORD" to "   "),
            )
        }
        assertTrue(error.message!!.contains("BITBUCKET_APP_PASSWORD"))
        assertFalse(error.message!!.contains("person@example.com"))
    }

    @Test
    fun `credential rendering is always redacted`() {
        val loaded = ServiceConfigurationLoader.load(defaults, credentials)
        val rendered = loaded.credentials.toString()
        assertFalse(rendered.contains("person@example.com"))
        assertFalse(rendered.contains("sentinel-api-token"))
        assertEquals("BitbucketCredentials(<redacted>)", rendered)
    }

    @Test
    fun `defaults and environment overrides are converted once`() {
        val loaded = ServiceConfigurationLoader.load(
            defaults,
            credentials + mapOf(
                "BITBUCKET_HELPER_HTTP_PORT" to "18080",
                "BITBUCKET_HELPER_DATABASE_PATH" to "build/test-state.sqlite",
                "BITBUCKET_HELPER_REFRESH_INTERVAL" to "PT30S",
            ),
        )
        assertEquals("127.0.0.1", loaded.httpHost)
        assertEquals(18080, loaded.httpPort)
        assertEquals(Path.of("build/test-state.sqlite").toAbsolutePath().normalize(), loaded.databasePath)
        assertEquals(Duration.ofSeconds(30), loaded.refreshInterval)
        assertEquals("https://api.bitbucket.org/2.0", loaded.bitbucketBaseUrl.toString())
        assertEquals(Duration.ofSeconds(30), loaded.bitbucketRequestTimeout)
    }

    @Test
    fun `invalid port and nonpositive duration identify only their setting`() {
        val portError = assertThrows(StartupConfigurationException::class.java) {
            ServiceConfigurationLoader.load(defaults, credentials + ("BITBUCKET_HELPER_HTTP_PORT" to "0"))
        }
        val durationError = assertThrows(StartupConfigurationException::class.java) {
            ServiceConfigurationLoader.load(defaults, credentials + ("BITBUCKET_HELPER_REFRESH_INTERVAL" to "PT0S"))
        }
        assertTrue(portError.message!!.contains("BITBUCKET_HELPER_HTTP_PORT"))
        assertTrue(durationError.message!!.contains("BITBUCKET_HELPER_REFRESH_INTERVAL"))
    }
}
```

- [ ] **Step 2: Run the focused test to verify RED**

Run: `./gradlew test --tests '*ServiceConfigurationTest'`

Expected: test compilation fails because the configuration types do not exist.

- [ ] **Step 3: Add non-secret HOCON defaults**

```hocon
bitbucket-helper {
  http {
    host = "127.0.0.1"
    port = 8080
  }
  database.path = "./var/bitbucket-helper.sqlite"
  refresh.interval = "PT15M"
  bitbucket {
    base-url = "https://api.bitbucket.org/2.0"
    request-timeout = "PT30S"
  }
}
```

- [ ] **Step 4: Implement side-effect-free typed loading**

Use ordinary `BitbucketCredentials`, not a data class, so generated `toString`, `equals`, or `copy` methods cannot expose the token:

```kotlin
package com.mindtable.bitbuckethelper.bootstrap

import com.typesafe.config.Config
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class StartupConfigurationException(message: String) : IllegalArgumentException(message)

class BitbucketCredentials(
    val username: String,
    val apiToken: String,
) {
    override fun toString(): String = "BitbucketCredentials(<redacted>)"
}

data class ServiceConfiguration(
    val httpHost: String,
    val httpPort: Int,
    val databasePath: Path,
    val refreshInterval: Duration,
    val bitbucketBaseUrl: URI,
    val bitbucketRequestTimeout: Duration,
    val credentials: BitbucketCredentials,
)

object ServiceConfigurationLoader {
    fun load(
        config: Config,
        environment: Map<String, String> = System.getenv(),
    ): ServiceConfiguration {
        val username = requiredSecret(environment, "BITBUCKET_USERNAME")
        val token = requiredSecret(environment, "BITBUCKET_APP_PASSWORD")
        val port = environment["BITBUCKET_HELPER_HTTP_PORT"]
            ?.let { parsePort(it) }
            ?: config.getInt("bitbucket-helper.http.port")
        requireConfig(port in 1..65535, "BITBUCKET_HELPER_HTTP_PORT must be between 1 and 65535")
        val interval = environment["BITBUCKET_HELPER_REFRESH_INTERVAL"]
            ?.let { parseDuration(it, "BITBUCKET_HELPER_REFRESH_INTERVAL") }
            ?: parseDuration(config.getString("bitbucket-helper.refresh.interval"), "bitbucket-helper.refresh.interval")
        requireConfig(!interval.isZero && !interval.isNegative, "BITBUCKET_HELPER_REFRESH_INTERVAL must be positive")
        val databasePath = Path.of(
            environment["BITBUCKET_HELPER_DATABASE_PATH"]
                ?: config.getString("bitbucket-helper.database.path"),
        ).toAbsolutePath().normalize()
        validateDatabaseLocation(databasePath)
        return ServiceConfiguration(
            httpHost = "127.0.0.1",
            httpPort = port,
            databasePath = databasePath,
            refreshInterval = interval,
            bitbucketBaseUrl = URI(config.getString("bitbucket-helper.bitbucket.base-url")),
            bitbucketRequestTimeout = parsePositiveDuration(
                config.getString("bitbucket-helper.bitbucket.request-timeout"),
                "bitbucket-helper.bitbucket.request-timeout",
            ),
            credentials = BitbucketCredentials(username, token),
        )
    }

    private fun requiredSecret(environment: Map<String, String>, name: String): String =
        environment[name]?.takeIf { it.isNotBlank() }
            ?: throw StartupConfigurationException("Required environment variable $name is missing or blank")

    private fun parsePort(raw: String): Int = raw.toIntOrNull()
        ?: throw StartupConfigurationException("BITBUCKET_HELPER_HTTP_PORT must be an integer")

    private fun parseDuration(raw: String, name: String): Duration =
        runCatching { Duration.parse(raw) }
            .getOrElse { throw StartupConfigurationException("$name must be an ISO-8601 duration") }

    private fun parsePositiveDuration(raw: String, name: String): Duration =
        parseDuration(raw, name).also {
            requireConfig(!it.isZero && !it.isNegative, "$name must be positive")
        }

    private fun validateDatabaseLocation(path: Path) {
        requireConfig(!Files.isDirectory(path), "BITBUCKET_HELPER_DATABASE_PATH must identify a file")
        var ancestor: Path? = path.parent
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.parent
        requireConfig(ancestor != null && Files.isDirectory(ancestor) && Files.isWritable(ancestor),
            "BITBUCKET_HELPER_DATABASE_PATH parent must be creatable or writable")
    }

    private fun requireConfig(condition: Boolean, message: String) {
        if (!condition) throw StartupConfigurationException(message)
    }
}
```

The loader must inspect paths only; it must not call `createDirectories`, open JDBC, or write a file.

- [ ] **Step 5: Verify GREEN and absence of side effects**

Run: `./gradlew test --tests '*ServiceConfigurationTest'`

Expected: all tests pass.

Run: `rg -n 'println|printStackTrace|credentials\}' src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap`

Expected: no output.

- [ ] **Step 6: Self-review and commit**

```bash
git add src/main/resources/application.conf src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceConfiguration.kt src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceConfigurationTest.kt
git commit -m "feat: validate service configuration before side effects"
```

---

### Task 3: Fakeable Application Ports and Refresh Use Cases

**Files:**
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/application/model/BitbucketConnectionModels.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/RefreshBitbucketConnection.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/GetBitbucketConnectionStatus.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/BitbucketAccountGateway.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/BitbucketConnectionRepository.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshBitbucketConnectionService.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/application/service/GetBitbucketConnectionStatusService.kt`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshBitbucketConnectionServiceTest.kt`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/application/service/GetBitbucketConnectionStatusServiceTest.kt`

**Interfaces:**
- Consumes: Kotlin/JDK only.
- Produces: `BitbucketAccount`, `ConnectionFailure`, `BitbucketConnectionSnapshot`, `BitbucketAccountResult`, `BitbucketAccountGateway.fetchCurrentAccount()`, repository `find/recordSuccess/recordFailure`, and callable inbound ports used by all adapters.

- [ ] **Step 1: Write failing refresh-service tests with in-memory fakes**

Use the fixed instant `2026-08-15T10:15:30Z`. The test must assert these exact transitions:

```kotlin
@Test
fun `success persists a healthy snapshot at the clock instant`() = runTest {
    val account = BitbucketAccount("{account-uuid}", "Ada Lovelace", null)
    val repository = FakeConnectionRepository()
    val service = RefreshBitbucketConnectionService(
        gateway = BitbucketAccountGateway { BitbucketAccountResult.Success(account) },
        repository = repository,
        clock = Clock.fixed(Instant.parse("2026-08-15T10:15:30Z"), ZoneOffset.UTC),
    )

    val result = service()

    assertEquals(ConnectionState.HEALTHY, result.state)
    assertEquals(account, result.account)
    assertEquals(Instant.parse("2026-08-15T10:15:30Z"), result.lastAttemptAt)
    assertEquals(result.lastAttemptAt, result.lastSuccessAt)
    assertNull(result.failure)
}

@Test
fun `failure records a sanitized category and delegates preservation to the repository`() = runTest {
    val failure = ConnectionFailure(ConnectionFailureCode.RATE_LIMITED, "Bitbucket rate limit exceeded")
    val repository = FakeConnectionRepository(existingHealthySnapshot())
    val service = RefreshBitbucketConnectionService(
        gateway = BitbucketAccountGateway { BitbucketAccountResult.Failure(failure) },
        repository = repository,
        clock = fixedClock,
    )

    val result = service()

    assertEquals(ConnectionState.FAILED, result.state)
    assertEquals(existingHealthySnapshot().account, result.account)
    assertEquals(existingHealthySnapshot().lastSuccessAt, result.lastSuccessAt)
    assertEquals(failure, result.failure)
}
```

Implement `FakeConnectionRepository` inside the test with the same semantics required of SQLite: success replaces account and both timestamps; failure preserves existing account and last-success timestamp.

- [ ] **Step 2: Write the failing query-service tests**

```kotlin
@Test
fun `query returns null when no snapshot exists`() = runTest {
    val repository = FakeConnectionRepository()
    val query = GetBitbucketConnectionStatusService(repository)

    assertNull(query())
}
```

- [ ] **Step 3: Run the application tests to verify RED**

Run: `./gradlew test --tests '*ConnectionServiceTest' --tests '*ConnectionStatusServiceTest'`

Expected: compilation fails because the application model and ports do not exist.

- [ ] **Step 4: Implement the stable application-owned model**

```kotlin
package com.mindtable.bitbuckethelper.application.model

import java.time.Instant

data class BitbucketAccount(
    val uuid: String,
    val displayName: String,
    val nickname: String?,
)

enum class ConnectionState { HEALTHY, FAILED }

enum class ConnectionFailureCode {
    AUTHENTICATION,
    AUTHORIZATION,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK,
    UPSTREAM,
    UNEXPECTED,
}

data class ConnectionFailure(
    val code: ConnectionFailureCode,
    val message: String,
)

data class BitbucketConnectionSnapshot(
    val state: ConnectionState,
    val account: BitbucketAccount?,
    val lastAttemptAt: Instant,
    val lastSuccessAt: Instant?,
    val failure: ConnectionFailure?,
)

sealed interface BitbucketAccountResult {
    data class Success(val account: BitbucketAccount) : BitbucketAccountResult
    data class Failure(val failure: ConnectionFailure) : BitbucketAccountResult
}
```

- [ ] **Step 5: Implement the ports with exact signatures**

```kotlin
fun interface BitbucketAccountGateway {
    suspend fun fetchCurrentAccount(): BitbucketAccountResult
}

interface BitbucketConnectionRepository {
    suspend fun find(): BitbucketConnectionSnapshot?
    suspend fun recordSuccess(account: BitbucketAccount, attemptedAt: Instant): BitbucketConnectionSnapshot
    suspend fun recordFailure(failure: ConnectionFailure, attemptedAt: Instant): BitbucketConnectionSnapshot
}

fun interface RefreshBitbucketConnection {
    suspend operator fun invoke(): BitbucketConnectionSnapshot
}

fun interface GetBitbucketConnectionStatus {
    suspend operator fun invoke(): BitbucketConnectionSnapshot?
}
```

Put each interface in the file and package named in the File and Responsibility Map; do not collapse them into the model file.

- [ ] **Step 6: Implement the two application services**

```kotlin
class RefreshBitbucketConnectionService(
    private val gateway: BitbucketAccountGateway,
    private val repository: BitbucketConnectionRepository,
    private val clock: Clock,
) : RefreshBitbucketConnection {
    override suspend fun invoke(): BitbucketConnectionSnapshot {
        val attemptedAt = clock.instant()
        return when (val result = gateway.fetchCurrentAccount()) {
            is BitbucketAccountResult.Success -> repository.recordSuccess(result.account, attemptedAt)
            is BitbucketAccountResult.Failure -> repository.recordFailure(result.failure, attemptedAt)
        }
    }
}

class GetBitbucketConnectionStatusService(
    private val repository: BitbucketConnectionRepository,
) : GetBitbucketConnectionStatus {
    override suspend fun invoke(): BitbucketConnectionSnapshot? = repository.find()
}
```

- [ ] **Step 7: Verify GREEN and architectural purity**

Run: `./gradlew test --tests '*ConnectionServiceTest' --tests '*ConnectionStatusServiceTest'`

Expected: all application tests pass.

Run: `rg -n 'io\.ktor|org\.quartz|liquibase|org\.jooq|org\.sqlite|\.generated' src/main/kotlin/com/mindtable/bitbuckethelper/application`

Expected: no output.

- [ ] **Step 8: Self-review and commit**

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/application src/test/kotlin/com/mindtable/bitbuckethelper/application
git commit -m "feat: add fakeable connection refresh use cases"
```

---

### Task 4: Liquibase XML Contract and Build-Time jOOQ Generation

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `src/main/resources/db/migration/V0001__create_bitbucket_connection_snapshot.xml`
- Create: `src/main/resources/db/migration/AGENTS.md`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqGenerationSmokeTest.kt`

**Interfaces:**
- Consumes: Gradle foundation from Task 1 and application model names from Task 3.
- Produces: Gradle tasks `validateMigrationNames`, `prepareJooqCodegenDatabase`, and `jooqCodegen`; generated top-level Kotlin table reference `generated.tables.references.BITBUCKET_CONNECTION_SNAPSHOT`; XML changelog consumed by Task 5 at runtime.

- [ ] **Step 1: Write a generated-schema smoke test before adding generation wiring**

```kotlin
package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated.tables.references.BITBUCKET_CONNECTION_SNAPSHOT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JooqGenerationSmokeTest {
    @Test
    fun `generated singleton table exposes every migration-owned column`() {
        assertEquals(
            listOf(
                "singleton_id",
                "state",
                "account_uuid",
                "display_name",
                "nickname",
                "last_attempt_at",
                "last_success_at",
                "failure_code",
                "failure_message",
            ),
            BITBUCKET_CONNECTION_SNAPSHOT.fields().map { it.name },
        )
    }
}
```

- [ ] **Step 2: Run the smoke test to verify RED**

Run: `./gradlew test --tests '*JooqGenerationSmokeTest'`

Expected: test compilation fails because the generated package does not exist.

- [ ] **Step 3: Add the XML-only migration authoring memory**

Create `src/main/resources/db/migration/AGENTS.md` with this exact policy:

```markdown
# Liquibase Migration Guidance

- Every migration in this directory is Liquibase XML; never add a `.sql` migration.
- Name versioned files `V%04d__<snake_case_description>.xml`.
- Example: `V0001__create_bitbucket_connection_snapshot.xml`.
- Filenames must match `^V[0-9]{4}__[a-z0-9]+(?:_[a-z0-9]+)*\.xml$`.
- Version `0000` and duplicate numeric prefixes are invalid; version gaps are allowed.
- Give every Liquibase changeSet a repository-unique ID and author.
- Include an explicit rollback whenever Liquibase can reverse the change safely.
- Never edit a migration after it has been applied; add a new versioned XML migration.
```

- [ ] **Step 4: Add the XML master changelog**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
    <includeAll
        path="../migration"
        relativeToChangelogFile="true"
        endsWithFilter=".xml"/>
</databaseChangeLog>
```

The `endsWithFilter` is mandatory: it excludes `AGENTS.md` while allowing only XML migration inputs.

- [ ] **Step 5: Add the first immutable XML migration**

Use one XML `changeSet` with SQLite DDL embedded in an XML `<sql>` change. The file extension and Liquibase source of truth remain XML; no companion SQL file is permitted.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
    <changeSet id="V0001-create-bitbucket-connection-snapshot" author="bitbucket-helper">
        <sql splitStatements="false"><![CDATA[
CREATE TABLE bitbucket_connection_snapshot (
    singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
    state TEXT NOT NULL CHECK (state IN ('healthy', 'failed')),
    account_uuid TEXT,
    display_name TEXT,
    nickname TEXT,
    last_attempt_at TEXT NOT NULL,
    last_success_at TEXT,
    failure_code TEXT CHECK (
        failure_code IS NULL OR failure_code IN (
            'authentication', 'authorization', 'rate_limited', 'timeout',
            'network', 'upstream', 'unexpected'
        )
    ),
    failure_message TEXT,
    CHECK (
        (account_uuid IS NULL AND display_name IS NULL AND nickname IS NULL AND last_success_at IS NULL)
        OR
        (account_uuid IS NOT NULL AND display_name IS NOT NULL AND last_success_at IS NOT NULL)
    ),
    CHECK (
        (state = 'healthy' AND account_uuid IS NOT NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR
        (state = 'failed' AND failure_code IS NOT NULL AND failure_message IS NOT NULL)
    )
);
        ]]></sql>
        <rollback>
            <dropTable tableName="bitbucket_connection_snapshot"/>
        </rollback>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 6: Add strict migration filename validation to Gradle**

Add a task equivalent to the following. It must validate every regular file in the directory except `AGENTS.md`, so a `.sql`, editor backup, or loosely named XML file fails the build.

```kotlin
val migrationDirectory = layout.projectDirectory.dir("src/main/resources/db/migration")
val migrationName = Regex("^V[0-9]{4}__[a-z0-9]+(?:_[a-z0-9]+)*\\.xml$")

val validateMigrationNames by tasks.registering {
    group = "verification"
    inputs.dir(migrationDirectory)
    doLast {
        val candidates = migrationDirectory.asFile.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name != "AGENTS.md" }
        val invalid = candidates.map { it.name }.filterNot(migrationName::matches)
        check(invalid.isEmpty()) { "Invalid migration filenames: ${invalid.sorted().joinToString()}" }
        val versions = candidates.map { it.name.substring(1, 5) }
        check("0000" !in versions) { "Migration version V0000 is forbidden" }
        val duplicates = versions.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        check(duplicates.isEmpty()) { "Duplicate migration versions: ${duplicates.sorted().joinToString()}" }
    }
}

tasks.named("check") { dependsOn(validateMigrationNames) }
```

Run: `./gradlew validateMigrationNames`

Expected: PASS. Then temporarily copy the versioned file to `src/main/resources/db/migration/V0001__duplicate.xml`, rerun, and expect failure naming duplicate `0001`; remove only that temporary copy before continuing.

- [ ] **Step 7: Wire a fresh Liquibase-migrated SQLite database into official jOOQ generation**

Add `liquibaseRuntime` and `jooqCodegen` dependencies, and configure this task graph:

```text
validateMigrationNames
  -> prepareJooqCodegenDatabase
  -> jooqCodegen
  -> compileKotlin and compileTestKotlin
```

The preparation task is a `JavaExec` invocation of `liquibase.integration.commandline.LiquibaseCommandLine`. Immediately before each execution it must create only `build/jooq-codegen`, delete only `build/jooq-codegen/bitbucket-helper.sqlite` with `Files.deleteIfExists`, and run:

```text
--url=jdbc:sqlite:<absolute build path>/jooq-codegen/bitbucket-helper.sqlite
--driver=org.sqlite.JDBC
--changelog-file=<absolute project path>/src/main/resources/db/changelog/db.changelog-master.xml
update
```

Configure the official jOOQ plugin with:

```kotlin
jooq {
    configuration {
        jdbc {
            driver = "org.sqlite.JDBC"
            url = "jdbc:sqlite:${layout.buildDirectory.file("jooq-codegen/bitbucket-helper.sqlite").get().asFile.absolutePath}"
        }
        generator {
            name = "org.jooq.codegen.KotlinGenerator"
            database { name = "org.jooq.meta.sqlite.SQLiteDatabase" }
            generate {
                isDeprecated = false
                isRecords = true
                isImmutablePojos = false
            }
            target {
                packageName = "com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated"
                directory = layout.buildDirectory.dir("generated/sources/jooq/main/kotlin").get().asFile.path
            }
        }
    }
}
```

Add `build/generated/sources/jooq/main/kotlin` to the main Kotlin source set. Neither preparation nor generation may read a second DDL source.

- [ ] **Step 8: Verify GREEN from an empty generated-output directory**

Run: `./gradlew clean test --tests '*JooqGenerationSmokeTest'`

Expected: validation, Liquibase preparation, jOOQ generation, Kotlin compilation, and the smoke test all pass.

Run: `git status --short build`

Expected: no output because every generated output is ignored.

- [ ] **Step 9: Self-review and commit**

```bash
git add build.gradle.kts src/main/resources/db src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqGenerationSmokeTest.kt
git commit -m "build: generate jOOQ from Liquibase XML migrations"
```

---

### Task 5: Runtime SQLite Migration and jOOQ Repository

**Files:**
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/SqliteDatabase.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqBitbucketConnectionRepository.kt`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/SqliteDatabaseTest.kt`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqBitbucketConnectionRepositoryTest.kt`

**Interfaces:**
- Consumes: `BitbucketConnectionRepository` and application models from Task 3; XML changelog and generated jOOQ table from Task 4.
- Produces: `SqliteDatabase.open(path: Path): SqliteDatabase`, `SqliteDatabase.migrate()`, `SqliteDatabase.dataSource`, and `JooqBitbucketConnectionRepository(dataSource, dispatcher)` for bootstrap.

- [ ] **Step 1: Write the failing migration test**

```kotlin
@TempDir
lateinit var temporaryDirectory: Path

@Test
fun `migration creates the singleton table and records V0001`() {
    val database = SqliteDatabase.open(temporaryDirectory.resolve("nested/state.sqlite"))

    database.migrate()

    database.dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='bitbucket_connection_snapshot'",
            ).use { result ->
                assertTrue(result.next())
                assertEquals(1, result.getInt(1))
            }
            statement.executeQuery(
                "SELECT COUNT(*) FROM databasechangelog WHERE id='V0001-create-bitbucket-connection-snapshot'",
            ).use { result ->
                assertTrue(result.next())
                assertEquals(1, result.getInt(1))
            }
        }
    }
}
```

Add a second test that opens a connection and asserts `PRAGMA foreign_keys` is `1` and `PRAGMA busy_timeout` is a positive bounded value no greater than `30000` milliseconds.

- [ ] **Step 2: Write failing repository contract tests**

Use a fresh migrated database and a two-thread `ExecutorCoroutineDispatcher`. Assert:

```kotlin
@Test
fun `find is null before the first refresh`() = runTest {
    assertNull(repository.find())
}

@Test
fun `success inserts and a later failure preserves last known good account`() = runTest {
    val firstAttempt = Instant.parse("2026-08-15T10:15:30Z")
    val failedAttempt = Instant.parse("2026-08-15T10:16:30Z")
    val account = BitbucketAccount("{account-uuid}", "Ada Lovelace", "ada")

    repository.recordSuccess(account, firstAttempt)
    val failed = repository.recordFailure(
        ConnectionFailure(ConnectionFailureCode.NETWORK, "Bitbucket is unreachable"),
        failedAttempt,
    )

    assertEquals(ConnectionState.FAILED, failed.state)
    assertEquals(account, failed.account)
    assertEquals(firstAttempt, failed.lastSuccessAt)
    assertEquals(failedAttempt, failed.lastAttemptAt)
    assertEquals(ConnectionFailureCode.NETWORK, failed.failure!!.code)
}

@Test
fun `failure before any success stores no account`() = runTest {
    val failed = repository.recordFailure(
        ConnectionFailure(ConnectionFailureCode.AUTHENTICATION, "Bitbucket rejected the credentials"),
        Instant.parse("2026-08-15T10:15:30Z"),
    )

    assertNull(failed.account)
    assertNull(failed.lastSuccessAt)
}
```

Also query every text column directly and assert the sentinel string `sentinel-api-token` is absent.

- [ ] **Step 3: Run the persistence tests to verify RED**

Run: `./gradlew test --tests '*SqliteDatabaseTest' --tests '*JooqBitbucketConnectionRepositoryTest'`

Expected: test compilation fails because both production adapters are absent.

- [ ] **Step 4: Implement SQLite creation and runtime Liquibase migration**

`SqliteDatabase.open` must create the configured parent directory, create an `SQLiteDataSource` with `foreign_keys=ON` and `busy_timeout=5000`, and return it without running migrations in the constructor. `migrate()` must use `ClassLoaderResourceAccessor` and `db/changelog/db.changelog-master.xml`:

```kotlin
class SqliteDatabase private constructor(
    val dataSource: SQLiteDataSource,
) : AutoCloseable {
    fun migrate() {
        dataSource.connection.use { connection ->
            Liquibase(
                "db/changelog/db.changelog-master.xml",
                ClassLoaderResourceAccessor(),
                JdbcConnection(connection),
            ).use { liquibase -> liquibase.update(Contexts(), LabelExpression()) }
        }
    }

    override fun close() = Unit

    companion object {
        fun open(path: Path): SqliteDatabase {
            Files.createDirectories(requireNotNull(path.parent))
            val sqlite = SQLiteConfig().apply {
                enforceForeignKeys(true)
                setBusyTimeout(5_000)
            }
            val source = SQLiteDataSource(sqlite).apply {
                url = "jdbc:sqlite:${path.toAbsolutePath().normalize()}"
            }
            return SqliteDatabase(source)
        }
    }
}
```

- [ ] **Step 5: Implement atomic jOOQ upserts on the bounded dispatcher**

Use `withContext(dispatcher)` for all JDBC work and `DSL.using(dataSource, SQLDialect.SQLITE)`. `recordSuccess` must upsert all account/timestamp fields and clear failure fields. `recordFailure` must insert a failed row when absent, or update only `state`, `last_attempt_at`, `failure_code`, and `failure_message` when present so account and `last_success_at` survive.

Map stable strings exactly:

```kotlin
private fun ConnectionState.databaseValue() = name.lowercase()
private fun ConnectionFailureCode.databaseValue() = name.lowercase()
private fun String.toFailureCode() = ConnectionFailureCode.valueOf(uppercase())
```

Every write must return the row read back inside the same jOOQ transaction. Map ISO-8601 text with `Instant.parse` and `Instant.toString`; never use the machine timezone.

- [ ] **Step 6: Verify GREEN and migration idempotence**

Run: `./gradlew test --tests '*SqliteDatabaseTest' --tests '*JooqBitbucketConnectionRepositoryTest'`

Expected: all tests pass.

Run the migration test twice in one process by calling `database.migrate()` twice; the second call must leave one V0001 row and one application table.

- [ ] **Step 7: Self-review and commit**

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence
git commit -m "feat: persist connection snapshots with SQLite and jOOQ"
```

---

### Task 6: Pinned Bitbucket Snapshot and Selective OpenAPI Generation

**Files:**
- Modify: `build.gradle.kts`
- Create: `specs/bitbucket-cloud/openapi.json`
- Create: `specs/bitbucket-cloud/README.md`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/OpenApiSnapshotContractTest.kt`

**Interfaces:**
- Consumes: OpenAPI Generator plugin and Kotlin/Ktor dependencies from Task 1.
- Produces: maintenance task `updateBitbucketOpenApiSpec`, build task `prepareBitbucketOpenApi`, generated `UsersApi.getCurrentUser()` and its referenced models below `build/generated/sources/bitbucket/src/main/kotlin`, and no committed generated code.

- [ ] **Step 1: Add a failing contract test for the committed and reduced documents**

The test must use `kotlinx.serialization.json` and assert all of the following:

```kotlin
@Test
fun `committed snapshot checksum and reduced operation are reproducible`() {
    val canonicalFile = Path.of("specs/bitbucket-cloud/openapi.json")
    val metadata = Path.of("specs/bitbucket-cloud/README.md").readText()
    val expectedSha = Regex("SHA-256: `([0-9a-f]{64})`")
        .find(metadata)?.groupValues?.get(1)
        ?: fail("README must contain the snapshot SHA-256")
    assertEquals(expectedSha, sha256(canonicalFile.readBytes()))

    val canonical = json.parseToJsonElement(canonicalFile.readText()).jsonObject
    assertEquals("2.0", canonical["swagger"]!!.jsonPrimitive.content)
    assertNotNull(canonical["paths"]!!.jsonObject["/user"]!!.jsonObject["get"])
    assertEquals(
        true,
        canonical["definitions"]!!.jsonObject["object"]!!.jsonObject["additionalProperties"]!!
            .jsonPrimitive.boolean,
    )

    val prepared = json.parseToJsonElement(
        Path.of("build/openapi/bitbucket-current-user.json").readText(),
    ).jsonObject
    assertEquals("2.0", prepared["swagger"]!!.jsonPrimitive.content)
    assertEquals(setOf("/user"), prepared["paths"]!!.jsonObject.keys)
    assertFalse(
        prepared["definitions"]!!.jsonObject["object"]!!.jsonObject
            .containsKey("additionalProperties"),
    )
    val operation = prepared["paths"]!!.jsonObject["/user"]!!.jsonObject["get"]!!.jsonObject
    assertEquals("getCurrentUser", operation["operationId"]!!.jsonPrimitive.content)
}
```

Implement `sha256` in the test with `MessageDigest.getInstance("SHA-256")` and lowercase two-character hex bytes. Make the `test` task depend on `prepareBitbucketOpenApi` once that task exists.

- [ ] **Step 2: Run the focused test to verify RED**

Run: `./gradlew test --tests '*OpenApiSnapshotContractTest'`

Expected: failure because the committed snapshot, metadata, and preparation task do not exist.

- [ ] **Step 3: Implement the explicit networked maintenance task**

`updateBitbucketOpenApiSpec` is the only task allowed to access the canonical URL. It is not a dependency of any other task. It must:

1. download `https://api.bitbucket.org/swagger.json` to a temporary file under `build/openapi-update`;
2. parse the candidate and require canonical Swagger/OpenAPI `2.0`, path `/user`, and method `get`;
3. atomically replace `specs/bitbucket-cloud/openapi.json` only after validation;
4. calculate lowercase SHA-256; and
5. write `specs/bitbucket-cloud/README.md` in this format, substituting the task-computed UTC date and checksum variables:

```markdown
# Bitbucket Cloud OpenAPI Snapshot

- Canonical source: `https://api.bitbucket.org/swagger.json`
- Retrieved (UTC): `<task-computed ISO date>`
- SHA-256: `<task-computed lowercase SHA-256>`
- Source format: `Swagger/OpenAPI 2.0`
- OpenAPI Generator validation version: `7.24.0`
- Generated client library: `jvm-ktor`
- Reduced-spec compatibility: `definitions.object.additionalProperties` omitted; canonical snapshot unchanged

## Update and review procedure

1. Run `./gradlew updateBitbucketOpenApiSpec` explicitly; normal builds never run it.
2. Review the complete `openapi.json` diff, especially `GET /user` and recursively referenced schemas.
3. Run `./gradlew clean check` to regenerate and compile the selected client.
4. Inspect `build/generated/sources/bitbucket/src/main/kotlin` before committing the snapshot and metadata together.
```

The angle-bracketed strings above are values emitted by the Gradle task, not literal committed text. The generated README must contain concrete values before commit.

- [ ] **Step 4: Run the explicit update once and review the candidate**

Run: `./gradlew updateBitbucketOpenApiSpec`

Expected: the two files under `specs/bitbucket-cloud` are created. This is the only networked build invocation in the task.

Run: `rg -n '"/user"|"get"|"account"' specs/bitbucket-cloud/openapi.json`

Expected: the canonical document contains `GET /user` and its account schema. Confirm the README checksum by running the focused contract test only after Step 5 supplies the preparation task.

- [ ] **Step 5: Implement deterministic build-time reduction without altering the snapshot**

Use Gradle's bundled `groovy.json.JsonSlurper` and `JsonOutput`; do not add a second parser version. Atlassian's canonical document is Swagger/OpenAPI 2.0, which OpenAPI Generator consumes directly. `prepareBitbucketOpenApi` must:

- parse only the committed `specs/bitbucket-cloud/openapi.json`;
- deep-copy exactly `paths./user.get`;
- inject `operationId = "getCurrentUser"` into that copied operation because Atlassian's canonical operation currently lacks one;
- preserve `swagger`, `info`, `host`, `basePath`, `schemes`, `consumes`, `produces`, the selected `Users` tag, applicable root/operation security, and `securityDefinitions`;
- recursively discover every local `$ref` beginning `#/definitions/`, `#/parameters/`, or `#/responses/`, copy exactly those referenced root entries, and reject external or unsupported references; and
- require the copied `definitions.object.additionalProperties` value to be `true`, then remove only that property from the copied reduced definition. This approved compatibility normalization prevents OpenAPI Generator 7.24.0 from emitting the illegal Kotlin hierarchy `interface Object : HashMap`; the canonical snapshot remains byte-for-byte unchanged; and
- write formatted JSON to `build/openapi/bitbucket-current-user.json`.

Use this recursion shape in `build.gradle.kts`:

```kotlin
fun collectSwaggerRefs(node: Any?, destination: MutableSet<String>) {
    when (node) {
        is Map<*, *> -> node.forEach { (key, value) ->
            if (key == "\$ref" && value is String) {
                val supportedPrefixes = setOf("#/definitions/", "#/parameters/", "#/responses/")
                check(supportedPrefixes.any { value.startsWith(it) }) {
                    "External or unsupported OpenAPI reference is not allowed: $value"
                }
                destination += value
            } else {
                collectSwaggerRefs(value, destination)
            }
        }
        is Iterable<*> -> node.forEach { collectSwaggerRefs(it, destination) }
    }
}

val prepareBitbucketOpenApi by tasks.registering {
    val source = layout.projectDirectory.file("specs/bitbucket-cloud/openapi.json")
    val target = layout.buildDirectory.file("openapi/bitbucket-current-user.json")
    inputs.file(source)
    outputs.file(target)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(source.asFile) as Map<String, Any?>
        val paths = root.getValue("paths") as Map<*, *>
        val userPath = paths.getValue("/user") as Map<*, *>
        val selectedGet = LinkedHashMap(userPath.getValue("get") as Map<String, Any?>)
        selectedGet["operationId"] = "getCurrentUser"

        val pending = linkedSetOf<String>()
        val processed = linkedSetOf<String>()
        val copied = linkedMapOf<String, MutableMap<String, Any?>>()
        collectSwaggerRefs(selectedGet, pending)
        while (true) {
            val ref = pending.firstOrNull { it !in processed } ?: break
            processed += ref
            val segments = ref.removePrefix("#/").split('/')
            check(segments.size == 2) { "Unsupported Swagger reference: $ref" }
            val value = ((root.getValue(segments[0]) as Map<*, *>).getValue(segments[1]))
            copied.getOrPut(segments[0]) { linkedMapOf() }[segments[1]] = value
            collectSwaggerRefs(value, pending)
        }

        @Suppress("UNCHECKED_CAST")
        val generatedObject = copied.getValue("definitions").getValue("object") as Map<String, Any?>
        check(generatedObject["additionalProperties"] == true) {
            "Expected canonical definitions.object.additionalProperties to be true"
        }
        copied.getValue("definitions")["object"] = LinkedHashMap(generatedObject).apply {
            remove("additionalProperties")
        }

        val reduced = linkedMapOf<String, Any?>(
            "swagger" to root.getValue("swagger"),
            "info" to root.getValue("info"),
            "host" to root["host"],
            "basePath" to root["basePath"],
            "schemes" to root["schemes"],
            "consumes" to root["consumes"],
            "produces" to root["produces"],
            "security" to root["security"],
            "tags" to (root["tags"] as? List<*>)?.filter {
                (it as? Map<*, *>)?.get("name") == "Users"
            },
            "paths" to linkedMapOf("/user" to linkedMapOf("get" to selectedGet)),
            "definitions" to copied["definitions"],
            "parameters" to copied["parameters"],
            "responses" to copied["responses"],
            "securityDefinitions" to root["securityDefinitions"],
        ).filterValues { it != null }
        target.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(reduced)))
        }
    }
}
```

Do not modify or normalize `openapi.json` itself.

- [ ] **Step 6: Configure the generated Kotlin client and compilation spike**

Add the generated client's versionless Java-time runtime module to the existing dependencies:

```kotlin
implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
```

Do not specify or add a Jackson version. Ktor `3.5.1` already supplies the Jackson BOM constraint, which aligns this module with the build's resolved Jackson `2.21.3` family.

Configure `openApiGenerate` with:

```kotlin
openApiGenerate {
    dependsOn(prepareBitbucketOpenApi)
    generatorName.set("kotlin")
    library.set("jvm-ktor")
    inputSpec.set(layout.buildDirectory.file("openapi/bitbucket-current-user.json").get().asFile.path)
    outputDir.set(layout.buildDirectory.dir("generated/sources/bitbucket").get().asFile.path)
    packageName.set("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated")
    apiPackage.set("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api")
    modelPackage.set("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model")
    invokerPackage.set("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.infrastructure")
    configOptions.set(
        mapOf(
            "dateLibrary" to "java8",
            "serializationLibrary" to "jackson",
            "useCoroutines" to "true",
            "omitGradleWrapper" to "true",
            "hideGenerationTimestamp" to "true",
        ),
    )
}
```

Add only `build/generated/sources/bitbucket/src/main/kotlin` to the main source set and make `compileKotlin` depend on `openApiGenerate`. Keep all generated package variants beneath the approved generated root.

Run: `./gradlew clean compileKotlin`

Expected: the pinned `jvm-ktor` generated API and referenced models compile with Ktor 3.5.1 and JDK 25 without edits or custom templates. Confirm the public method is `UsersApi.getCurrentUser()`.

Retain `jvm-ktor`. Its clean compilation is proven by the approved reduced-only `definitions.object` normalization and the BOM-aligned Java-time module above. Do not select `jvm-okhttp4`: its recorded spike generated the same illegal model hierarchy and additionally required an unapproved OkHttp runtime dependency. Do not change templates, dependencies, or versions beyond the two approved compatibility adaptations.

- [ ] **Step 7: Verify GREEN, selectivity, and offline normal-build inputs**

Run: `./gradlew test --tests '*OpenApiSnapshotContractTest'`

Expected: checksum and reduced-operation assertions pass.

Run: `find build/generated/sources/bitbucket -type f -name '*Api.kt' -print`

Expected: exactly one generated API class for the Users tag and no API for repositories, pull requests, workspaces, or webhooks.

Run: `git status --short build`

Expected: no generated file is tracked.

- [ ] **Step 8: Self-review and commit**

```bash
git add build.gradle.kts specs/bitbucket-cloud src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/OpenApiSnapshotContractTest.kt
git commit -m "build: generate the current-user Bitbucket client"
```

---

### Task 7: Handwritten Generated-Client Adapter

**Files:**
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketAccountGateway.kt`
- Create: `src/test/resources/bitbucket/current-user-success.json`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketAccountGatewayTest.kt`

**Interfaces:**
- Consumes: `BitbucketAccountGateway` and result/failure models from Task 3; generated `suspend UsersApi.getCurrentUser(): generated.infrastructure.HttpResponse<Account>` from Task 6.
- Produces: `GeneratedBitbucketAccountGateway.create(baseUrl: URI, requestTimeout: Duration, username: String, apiToken: String)` implementing `BitbucketAccountGateway` and `AutoCloseable` for Task 10.

- [ ] **Step 1: Add a complete successful Bitbucket fixture**

```json
{
  "type": "user",
  "display_name": "Ada Lovelace",
  "uuid": "{account-uuid}",
  "account_id": "557057:example",
  "nickname": "ada",
  "created_on": "2017-06-29T21:04:09.970854+00:00",
  "links": {
    "self": { "href": "https://api.bitbucket.org/2.0/users/{account-uuid}" },
    "html": { "href": "https://bitbucket.org/ada" },
    "avatar": { "href": "https://bitbucket.org/account/ada/avatar/" }
  }
}
```

- [ ] **Step 2: Write a failing local-server success and Basic-auth test**

Start `com.sun.net.httpserver.HttpServer` on loopback port `0`. Register `/2.0/user`, capture the method and `Authorization` header, and respond with the fixture as `application/json`.

```kotlin
@Test
fun `generated client calls current user with API-token Basic auth and maps immediately`() = runTest {
    val token = "sentinel-api-token"
    val gateway = GeneratedBitbucketAccountGateway.create(
        baseUrl = URI("http://127.0.0.1:${server.address.port}/2.0"),
        requestTimeout = Duration.ofSeconds(2),
        username = "person@example.com",
        apiToken = token,
    )

    val result = gateway.fetchCurrentAccount()

    assertEquals("GET", capturedMethod.get())
    assertEquals(
        "Basic " + Base64.getEncoder().encodeToString("person@example.com:$token".toByteArray(UTF_8)),
        capturedAuthorization.get(),
    )
    assertEquals(
        BitbucketAccountResult.Success(
            BitbucketAccount("{account-uuid}", "Ada Lovelace", null),
        ),
        result,
    )
    gateway.close()
}
```

The canonical `account` schema does not expose `nickname`; the handwritten application model therefore receives `null` even if Bitbucket includes that extra JSON member.

- [ ] **Step 3: Write failing sanitized-failure tests**

Parameterize HTTP responses and expected categories:

```text
401 -> AUTHENTICATION / "Bitbucket rejected the credentials"
403 -> AUTHORIZATION / "Bitbucket denied the required permission"
429 -> RATE_LIMITED / "Bitbucket rate limit exceeded"
500 -> UPSTREAM / "Bitbucket service failed"
```

Use a response body containing both `sentinel-api-token` and an `Authorization` string. Assert neither appears in the returned failure, thrown exception text, or captured logs. Add a timeout test whose handler signals that it received the request and then blocks on a latch longer than the configured request timeout; assert `TIMEOUT / "Bitbucket request timed out"`. Add a closed-loopback-port test and assert `NETWORK / "Bitbucket is unreachable"`.

- [ ] **Step 4: Run the focused adapter tests to verify RED**

Run: `./gradlew test --tests '*GeneratedBitbucketAccountGatewayTest'`

Expected: test compilation fails because the handwritten gateway does not exist.

- [ ] **Step 5: Implement the generated-client boundary and secure HTTP client**

`GeneratedBitbucketAccountGateway` must be the only handwritten production class that imports `...bitbucket.generated.*`. Its `create` factory must:

- encode `username:apiToken` with UTF-8 Base64 before client construction;
- manually create and retain a CIO `HttpClientEngine`, then pass that engine and the injectable base URL to `UsersApi`;
- configure the generated client's private `HttpClient` only through `httpClientConfig`, installing the precomputed `Authorization: Basic ...` header plus bounded connect and request timeouts derived from `requestTimeout`;
- add no other custom headers or plugins, leave Ktor logging uninstalled, and disable any generated debug logging; and
- explicitly close the retained engine from `close()` without claiming ownership of, or access to, the generated client's private `HttpClient`.

Its core mapping is:

```kotlin
override suspend fun fetchCurrentAccount(): BitbucketAccountResult = try {
    val response = usersApi.getCurrentUser()
    if (!response.success) {
        response.response.cancel()
        return mapHttpFailure(response.status)
    }
    val generated = response.body()
    BitbucketAccountResult.Success(
        BitbucketAccount(
            uuid = requireNotNull(generated.uuid) { "Bitbucket account UUID was absent" },
            displayName = requireNotNull(generated.displayName) { "Bitbucket display name was absent" },
            nickname = null,
        ),
    )
} catch (failure: Exception) {
    mapFailure(failure)
}
```

Check `response.success` and `response.status` before calling `response.body()`. For a non-success response, cancel the underlying Ktor response/call as shown so its raw response channel is released without deserializing the body or reading/copying its body or headers. Map the integer status directly: `401` to `AUTHENTICATION`, `403` to `AUTHORIZATION`, `429` to `RATE_LIMITED`, every `5xx` to `UPSTREAM`, and every other non-success status to `UNEXPECTED`, using the stable messages specified by the tests. Call `response.body()` only on success, then immediately map the generated `Account` into the application model.

Re-throw `CancellationException`. Map thrown Ktor timeout exceptions to `TIMEOUT`, connection/I/O exceptions to `NETWORK`, and all remaining thrown transport or deserialization exceptions to `UNEXPECTED / "Bitbucket request failed unexpectedly"`. Never copy an exception message, response body, headers, URL user-info, or cause text into the application failure.

- [ ] **Step 6: Verify GREEN and secret containment**

Run: `./gradlew test --tests '*GeneratedBitbucketAccountGatewayTest'`

Expected: success, failure mapping, timeout, and network tests pass without contacting Bitbucket Cloud.

Run: `rg -n 'sentinel-api-token|BITBUCKET_APP_PASSWORD|printStackTrace|Logging' src/main src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket`

Expected: the sentinel appears only in tests; the environment variable is not read by the adapter; no request logger or stack trace exists.

- [ ] **Step 7: Self-review and commit**

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketAccountGateway.kt src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketAccountGatewayTest.kt src/test/resources/bitbucket/current-user-success.json
git commit -m "feat: adapt the generated Bitbucket account client"
```

---

### Task 8: Ktor Status Endpoint with Body-Level Business State

**Files:**
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BitbucketStatusRoutes.kt`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BitbucketStatusRoutesTest.kt`

**Interfaces:**
- Consumes: `GetBitbucketConnectionStatus` and application snapshot models from Task 3.
- Produces: `fun Application.installBitbucketStatusApi(getStatus: GetBitbucketConnectionStatus)` and version-1 serializable response DTOs for Task 10.

- [ ] **Step 1: Write failing tests for pending, healthy, and failed bodies**

Use Ktor `testApplication` and an injected lambda query. Pending must assert the entire JSON document, including explicit nulls:

```kotlin
@Test
fun `pending is a successful versioned query`() = testApplication {
    application { installBitbucketStatusApi { null } }

    val response = client.get("/api/v1/bitbucket/status")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(
        buildJsonObject {
            put("schemaVersion", 1)
            put("state", "pending")
            put("lastAttemptAt", JsonNull)
            put("lastSuccessAt", JsonNull)
            put("account", JsonNull)
            put("failure", JsonNull)
        },
        json.parseToJsonElement(response.bodyAsText()),
    )
}
```

Healthy must assert status `200`, lowercase `healthy`, UTC ISO-8601 timestamps, account UUID/display name/nickname, and null failure. Failed must assert status `200`, lowercase `failed`, preserved account/last-success data, and failure `{ "code": "rate_limited", "message": "Bitbucket rate limit exceeded" }`.

- [ ] **Step 2: Write the failing unexpected-error test**

```kotlin
@Test
fun `unexpected query failure is a sanitized 500`() = testApplication {
    application {
        installBitbucketStatusApi {
            error("sentinel-api-token and database internals")
        }
    }

    val response = client.get("/api/v1/bitbucket/status")

    assertEquals(HttpStatusCode.InternalServerError, response.status)
    assertEquals(
        """{"schemaVersion":1,"error":"internal_server_error"}""",
        response.bodyAsText(),
    )
    assertFalse(response.bodyAsText().contains("sentinel-api-token"))
}
```

- [ ] **Step 3: Run the route tests to verify RED**

Run: `./gradlew test --tests '*BitbucketStatusRoutesTest'`

Expected: test compilation fails because `installBitbucketStatusApi` does not exist.

- [ ] **Step 4: Implement explicit version-1 transport DTOs and mapping**

```kotlin
@Serializable
data class BitbucketStatusResponse(
    val schemaVersion: Int = 1,
    val state: String,
    val lastAttemptAt: String?,
    val lastSuccessAt: String?,
    val account: AccountResponse?,
    val failure: FailureResponse?,
)

@Serializable
data class AccountResponse(
    val uuid: String,
    val displayName: String,
    val nickname: String?,
)

@Serializable
data class FailureResponse(
    val code: String,
    val message: String,
)

@Serializable
private data class InternalErrorResponse(
    val schemaVersion: Int = 1,
    val error: String = "internal_server_error",
)
```

Map a `null` application snapshot to pending with all nullable fields null. Map an existing snapshot with `state.name.lowercase()`, `failure.code.name.lowercase()`, and `Instant.toString()`.

- [ ] **Step 5: Implement Ktor plugins and the one GET route**

```kotlin
fun Application.installBitbucketStatusApi(
    getStatus: GetBitbucketConnectionStatus,
) {
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true; explicitNulls = true })
    }
    install(StatusPages) {
        exception<Exception> { call, cause ->
            if (cause is CancellationException) throw cause
            call.respond(HttpStatusCode.InternalServerError, InternalErrorResponse())
        }
    }
    routing {
        get("/api/v1/bitbucket/status") {
            call.respond(HttpStatusCode.OK, getStatus().toResponse())
        }
    }
}
```

Do not add health semantics to the HTTP status. Do not add CORS, externally bound hosts, mutation routes, or future product endpoints.

- [ ] **Step 6: Verify GREEN and status-code policy**

Run: `./gradlew test --tests '*BitbucketStatusRoutesTest'`

Expected: all four route tests pass.

Run: `rg -n 'Accepted|Conflict|ServiceUnavailable|HttpStatusCode\.(Accepted|Conflict|ServiceUnavailable)' src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http`

Expected: no output.

- [ ] **Step 7: Self-review and commit**

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BitbucketStatusRoutes.kt src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BitbucketStatusRoutesTest.kt
git commit -m "feat: expose versioned Bitbucket connection status"
```

---

### Task 9: Quartz Immediate Repeating Scheduler

**Files:**
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/RefreshBitbucketConnectionJob.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/UseCaseJobFactory.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/QuartzRefreshScheduler.kt`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/QuartzRefreshSchedulerTest.kt`

**Interfaces:**
- Consumes: `RefreshBitbucketConnection` from Task 3.
- Produces: `QuartzRefreshScheduler.create(refresh, refreshInterval, jobTimeout)`, `start()`, and `close()` for Task 10.

- [ ] **Step 1: Write a failing immediate-execution test without sleeps**

```kotlin
@Test
fun `start schedules and awaits an immediate refresh`() = runTest {
    val invoked = CompletableDeferred<Unit>()
    val scheduler = QuartzRefreshScheduler.create(
        refresh = RefreshBitbucketConnection {
            invoked.complete(Unit)
            healthySnapshot()
        },
        refreshInterval = Duration.ofMinutes(15),
        jobTimeout = Duration.ofSeconds(2),
    )

    scheduler.start()

    withTimeout(2.seconds) { invoked.await() }
    scheduler.close()
}
```

The fixture `healthySnapshot()` may live in the test file and must use the same application types as Task 3.

- [ ] **Step 2: Write failing non-overlap and trigger-policy tests**

Use `AtomicInteger` for current and maximum concurrency plus `CompletableDeferred` gates. Configure a 20-millisecond test interval, block the first invocation until the test releases it, wait for two completed invocations under `withTimeout`, and assert maximum concurrency is exactly `1`. Do not assert completion after a fixed `Thread.sleep`.

Expose the scheduled trigger through an internal test accessor or query it from the scheduler by its fixed key. Assert:

```kotlin
assertEquals(SimpleTrigger.REPEAT_INDEFINITELY, trigger.repeatCount)
assertEquals(
    SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT,
    trigger.misfireInstruction,
)
```

The smart-policy constant is not accepted because it can create implementation-dependent catch-up behavior.

- [ ] **Step 3: Run scheduler tests to verify RED**

Run: `./gradlew test --tests '*QuartzRefreshSchedulerTest'`

Expected: test compilation fails because the scheduler adapter does not exist.

- [ ] **Step 4: Implement the awaited, non-concurrent Quartz job**

```kotlin
@DisallowConcurrentExecution
class RefreshBitbucketConnectionJob(
    private val refresh: RefreshBitbucketConnection,
    private val timeout: Duration,
) : Job {
    override fun execute(context: JobExecutionContext) {
        runBlocking {
            withTimeout(timeout.toMillis()) {
                refresh()
            }
        }
    }
}
```

Do not call `launch`, `GlobalScope`, or return before `refresh()` completes. A repository exception may fail that Quartz execution; do not request immediate refire.

- [ ] **Step 5: Inject the use case with a custom job factory**

```kotlin
class UseCaseJobFactory(
    private val refresh: RefreshBitbucketConnection,
    private val timeout: Duration,
) : JobFactory {
    override fun newJob(bundle: TriggerFiredBundle, scheduler: Scheduler): Job {
        check(bundle.jobDetail.jobClass == RefreshBitbucketConnectionJob::class.java)
        return RefreshBitbucketConnectionJob(refresh, timeout)
    }
}
```

No static registry, singleton locator, or reflective no-argument construction is allowed.

- [ ] **Step 6: Implement RAMJobStore lifecycle and skip-catch-up trigger**

Use fixed keys `bitbucket-current-user-refresh` in group `bitbucket-helper`. Construct `StdSchedulerFactory` with a unique instance name, `org.quartz.simpl.SimpleThreadPool`, two worker threads, and `org.quartz.simpl.RAMJobStore`. Set the custom job factory before scheduling.

Create the trigger with:

```kotlin
SimpleScheduleBuilder.simpleSchedule()
    .withIntervalInMilliseconds(refreshInterval.toMillis())
    .repeatForever()
    .withMisfireHandlingInstructionNextWithRemainingCount()
```

Use `startNow()`. `start()` must be idempotent or reject a second start clearly. `close()` must call `shutdown(true)` once; the bounded job timeout prevents unbounded active-job shutdown.

- [ ] **Step 7: Verify GREEN and coroutine discipline**

Run: `./gradlew test --tests '*QuartzRefreshSchedulerTest'`

Expected: immediate execution, repeat policy, and non-overlap tests pass.

Run: `rg -n 'GlobalScope|launch\s*\{|async\s*\{' src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler`

Expected: no output.

- [ ] **Step 8: Self-review and commit**

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler
git commit -m "feat: schedule awaited Bitbucket refreshes with Quartz"
```

---

### Task 10: Production Composition and Full Walking-Skeleton Test

**Files:**
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Commands.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Main.kt`
- Test: `src/test/kotlin/com/mindtable/bitbuckethelper/WalkingSkeletonEndToEndTest.kt`

**Interfaces:**
- Consumes: typed configuration from Task 2, application services from Task 3, SQLite/jOOQ from Task 5, generated gateway from Task 7, Ktor module from Task 8, and Quartz scheduler from Task 9.
- Produces: `ServiceRuntime.create(configuration, clock)`, `start()`, `resolvedHttpPort()`, `close()`, and a production `service run` process path.

- [ ] **Step 1: Write the failing full walking-skeleton test**

The test must use a real local `HttpServer`, a temporary SQLite path, the production `ServiceRuntime`, and Java's HTTP client. Do not replace any adapter after composition.

```kotlin
@Test
fun `immediate Quartz refresh crosses generated client SQLite and Ktor`(@TempDir directory: Path) = runTest {
    val token = "sentinel-api-token"
    val databasePath = directory.resolve("state.sqlite")
    val fakeBitbucket = FakeBitbucketServer.success(
        resource = "/bitbucket/current-user-success.json",
    )
    val runtime = ServiceRuntime.create(
        configuration = ServiceConfiguration(
            httpHost = "127.0.0.1",
            httpPort = 0,
            databasePath = databasePath,
            refreshInterval = Duration.ofMinutes(15),
            bitbucketBaseUrl = fakeBitbucket.baseUrl,
            bitbucketRequestTimeout = Duration.ofSeconds(2),
            credentials = BitbucketCredentials("person@example.com", token),
        ),
        clock = Clock.fixed(Instant.parse("2026-08-15T10:15:30Z"), ZoneOffset.UTC),
    )

    try {
        runtime.start()
        val port = runtime.resolvedHttpPort()
        val response = eventuallyWithin(Duration.ofSeconds(5)) {
            httpGet("http://127.0.0.1:$port/api/v1/bitbucket/status")
                .takeIf { it.statusCode() == 200 && it.body().contains("\"state\":\"healthy\"") }
        }

        assertTrue(response.body().contains("{account-uuid}"))
        assertTrue(response.body().contains("Ada Lovelace"))
        assertFalse(response.body().contains(token))
        assertEquals("GET", fakeBitbucket.capturedMethod)
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("person@example.com:$token".toByteArray(UTF_8)),
            fakeBitbucket.capturedAuthorization,
        )
        assertDatabaseSnapshot(databasePath, state = "healthy", accountUuid = "{account-uuid}")
        assertDatabaseDoesNotContain(databasePath, token)
    } finally {
        runtime.close()
        fakeBitbucket.close()
    }
}
```

Implement `eventuallyWithin` as a monotonic-deadline loop that returns only when the supplied nullable result is non-null, yields between attempts with a short coroutine delay, and throws at the deadline. It waits on an observable condition rather than assuming completion after one fixed sleep.

Capture `System.out` and `System.err` around runtime startup/close and assert the token and full Authorization header are absent. Restore both streams in `finally` even when an assertion fails.

- [ ] **Step 2: Run the end-to-end test to verify RED**

Run: `./gradlew test --tests '*WalkingSkeletonEndToEndTest'`

Expected: test compilation fails because `ServiceRuntime` does not exist.

- [ ] **Step 3: Implement ordered production composition**

`ServiceRuntime.create` must execute or construct in this order:

1. `SqliteDatabase.open(configuration.databasePath)` and `migrate()`;
2. a bounded two-thread JDBC `ExecutorCoroutineDispatcher`;
3. `JooqBitbucketConnectionRepository`;
4. `GeneratedBitbucketAccountGateway.create` with configuration fields;
5. `RefreshBitbucketConnectionService` and `GetBitbucketConnectionStatusService`;
6. `QuartzRefreshScheduler` with `jobTimeout = bitbucketRequestTimeout.plusSeconds(5)`; and
7. an unstarted Ktor CIO embedded server bound to the configured loopback host/port with `installBitbucketStatusApi(query)`.

The factory must catch partial-composition failures and close already-created resources in reverse order. Do not log the `ServiceConfiguration`, `BitbucketCredentials`, or caught exception message.

Use this lifecycle surface:

```kotlin
class ServiceRuntime private constructor(
    private val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
    private val scheduler: QuartzRefreshScheduler,
    private val bitbucketGateway: GeneratedBitbucketAccountGateway,
    private val databaseDispatcher: ExecutorCoroutineDispatcher,
    private val database: SqliteDatabase,
) : AutoCloseable {
    private var started = false
    private var closed = false

    fun start() {
        check(!closed) { "Service runtime is closed" }
        check(!started) { "Service runtime is already started" }
        scheduler.start()
        try {
            server.start(wait = false)
            started = true
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    suspend fun resolvedHttpPort(): Int = server.resolvedConnectors().single().port

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000) }
        runCatching { scheduler.close() }
        runCatching { bitbucketGateway.close() }
        runCatching { databaseDispatcher.close() }
        runCatching { database.close() }
    }
}
```

- [ ] **Step 4: Replace the temporary Clikt callback with sanitized production startup**

Add `fun runConfiguredService(environment: Map<String, String> = System.getenv())` in `Commands.kt`. It must load `application.conf`, call `ServiceConfigurationLoader.load` before `ServiceRuntime.create`, install one JVM shutdown hook, start the runtime, and wait on a latch until shutdown. Its `finally` block removes the hook when possible and closes the runtime once.

Replace `Main.kt` with:

```kotlin
package com.mindtable.bitbuckethelper.bootstrap

import com.github.ajalt.clikt.core.main
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        rootCommand(::runConfiguredService).main(args)
    } catch (failure: StartupConfigurationException) {
        System.err.println("Configuration error: ${failure.message}")
        exitProcess(2)
    } catch (failure: Throwable) {
        System.err.println("Service startup failed")
        exitProcess(1)
    }
}
```

Do not print the general exception class, message, stack trace, configuration, or causes. Configuration exceptions are already constrained to setting names and generic validation text.

- [ ] **Step 5: Verify GREEN and clean shutdown**

Run: `./gradlew test --tests '*WalkingSkeletonEndToEndTest'`

Expected: the immediate Quartz execution reaches the fake server, persists through jOOQ, appears through Ktor as healthy `200`, contains no token outside the fake's expected captured header, and all resources stop within the test timeout.

Run: `./gradlew test --tests '*RootCommandTest'`

Expected: the command-tree tests still pass.

- [ ] **Step 6: Self-review and commit**

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Commands.kt src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Main.kt src/test/kotlin/com/mindtable/bitbuckethelper/WalkingSkeletonEndToEndTest.kt
git commit -m "feat: compose the full Kotlin walking skeleton"
```

---

### Task 11: Process Fail-Fast, Architecture Rules, Documentation, and Acceptance

**Files:**
- Modify: `build.gradle.kts`
- Modify: `README.md`
- Create: `src/test/kotlin/com/mindtable/bitbuckethelper/MissingCredentialsProcessTest.kt`
- Create: `src/test/kotlin/com/mindtable/bitbuckethelper/ArchitectureTest.kt`

**Interfaces:**
- Consumes: the completed service and fat-JAR configuration from Tasks 1-10.
- Produces: executable package acceptance, credential-before-database proof, package-boundary enforcement, and operator instructions.

- [ ] **Step 1: Write the failing fat-JAR process tests**

Make the test task depend on `buildFatJar` without making `buildFatJar` depend on tests. Locate exactly one `*-all.jar` under `build/libs`. For each case, start:

```text
<current java executable> -jar <fat jar> service run
```

Clear both credential variables from the inherited environment before adding the case-specific value. Set `BITBUCKET_HELPER_DATABASE_PATH` to a nonexistent child of `@TempDir`.

```kotlin
@Test
fun `missing username exits before database creation`(@TempDir directory: Path) {
    val result = runFatJar(
        directory = directory,
        environment = mapOf("BITBUCKET_APP_PASSWORD" to "sentinel-api-token"),
    )
    assertTrue(result.finishedWithinTenSeconds)
    assertNotEquals(0, result.exitCode)
    assertTrue(result.output.contains("BITBUCKET_USERNAME"))
    assertFalse(result.output.contains("sentinel-api-token"))
    assertFalse(Files.exists(directory.resolve("state.sqlite")))
}

@Test
fun `missing legacy-named token exits before database creation`(@TempDir directory: Path) {
    val result = runFatJar(
        directory = directory,
        environment = mapOf("BITBUCKET_USERNAME" to "person@example.com"),
    )
    assertTrue(result.finishedWithinTenSeconds)
    assertNotEquals(0, result.exitCode)
    assertTrue(result.output.contains("BITBUCKET_APP_PASSWORD"))
    assertFalse(result.output.contains("person@example.com"))
    assertFalse(Files.exists(directory.resolve("state.sqlite")))
}
```

On a timeout, destroy only the spawned test process, wait for it, and fail with sanitized output.

- [ ] **Step 2: Run the process tests to verify RED**

Run: `./gradlew test --tests '*MissingCredentialsProcessTest'`

Expected: failure until the test/fat-JAR dependency and process helper are complete; once wired, any database created before validation must make the test fail.

- [ ] **Step 3: Write architecture tests for dependency direction and generated containment**

```kotlin
@AnalyzeClasses(packages = ["com.mindtable.bitbuckethelper"])
class ArchitectureTest {
    @ArchTest
    val application_has_no_framework_dependencies: ArchRule = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "io.ktor..",
            "org.quartz..",
            "liquibase..",
            "org.jooq..",
            "org.sqlite..",
            "..adapter..",
            "..bootstrap..",
        )

    @ArchTest
    val generated_bitbucket_types_are_adapter_private: ArchRule = noClasses()
        .that().resideOutsideOfPackage("..adapter.outbound.bitbucket..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..adapter.outbound.bitbucket.generated..",
        )

    @ArchTest
    val generated_jooq_types_are_adapter_private: ArchRule = noClasses()
        .that().resideOutsideOfPackage("..adapter.outbound.persistence..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..adapter.outbound.persistence.generated..",
        )

    @ArchTest
    val top_level_packages_are_acyclic: ArchRule = slices()
        .matching("com.mindtable.bitbuckethelper.(*)..")
        .should().beFreeOfCycles()
}
```

Add an adapter rule preventing `..adapter..` from depending on `..bootstrap..`. If no domain classes exist, do not create an empty marker solely for ArchUnit.

```kotlin
@ArchTest
val adapters_do_not_depend_on_bootstrap: ArchRule = noClasses()
    .that().resideInAPackage("..adapter..")
    .should().dependOnClassesThat().resideInAPackage("..bootstrap..")
```

- [ ] **Step 4: Run architecture and process tests to verify GREEN**

Run: `./gradlew test --tests '*ArchitectureTest' --tests '*MissingCredentialsProcessTest'`

Expected: all rules and both pre-database process failures pass.

- [ ] **Step 5: Replace README foundation-pending text with runnable instructions**

Document:

```bash
export BITBUCKET_USERNAME='person@example.com'
export BITBUCKET_APP_PASSWORD='<current Bitbucket API token>'
./gradlew clean check
./gradlew buildFatJar
java -jar build/libs/bitbucket-helper-0.1.0-all.jar service run
curl http://127.0.0.1:8080/api/v1/bitbucket/status
```

State plainly that `BITBUCKET_APP_PASSWORD` is a legacy variable name containing a current API token, not a retired app password; it needs `read:user:bitbucket`. Describe the three `200 OK` body states. Link the approved walking-skeleton design and this implementation plan. Keep the Testcontainers suite described only as a deferred follow-up and do not add implementation instructions for it.

- [ ] **Step 6: Run the complete clean acceptance suite**

Run: `./gradlew clean check`

Expected: migration validation, fresh Liquibase database creation, jOOQ generation, reduced OpenAPI generation, compilation, every unit/integration/process test, and ArchUnit all pass from an empty `build/` directory without contacting Bitbucket Cloud.

Run: `./gradlew buildFatJar`

Expected: exactly one runnable `build/libs/bitbucket-helper-0.1.0-all.jar`.

Run: `java -jar build/libs/bitbucket-helper-0.1.0-all.jar --version`

Expected: exit `0` and version `0.1.0`.

Run: `java -jar build/libs/bitbucket-helper-0.1.0-all.jar --help`

Expected: exit `0` and the `service` command is listed.

Run: `git ls-files build specs/bitbucket-cloud src/main/resources/db/migration`

Expected: no `build/` path; the canonical OpenAPI snapshot/metadata, migration XML, and migration `AGENTS.md` are tracked.

Run: `rg -n 'testcontainers|org\.testcontainers' build.gradle.kts gradle src`

Expected: no output.

Run: `git diff --check`

Expected: no whitespace errors.

- [ ] **Step 7: Self-review and commit**

Confirm `git status --short` still shows the user's protected root changes only outside this branch's implementation files and that `source/` was never added.

```bash
git add build.gradle.kts README.md src/test/kotlin/com/mindtable/bitbuckethelper/MissingCredentialsProcessTest.kt src/test/kotlin/com/mindtable/bitbuckethelper/ArchitectureTest.kt
git commit -m "test: verify packaged walking skeleton acceptance"
```

---

## Final Review Gate

After Task 11 is task-reviewed, dispatch one fresh whole-branch reviewer with the approved design, this plan, the base commit, and the final commit. The reviewer must independently run `./gradlew clean check` and `./gradlew buildFatJar`, inspect credential ordering and secret containment, confirm all valid connection states use HTTP `200`, confirm both generators reconstruct from committed sources, and verify no Testcontainers or protected `source/` work entered the branch. Resolve every blocking finding through the same implementer/re-review loop before using the branch-finishing workflow.
