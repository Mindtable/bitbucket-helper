package com.mindtable.bitbuckethelper.observability

import com.mindtable.bitbuckethelper.bootstrap.LoggingConfiguration
import com.mindtable.bitbuckethelper.bootstrap.ServiceLogLevel
import com.mindtable.bitbuckethelper.bootstrap.ServiceLogging
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.ConfigurationFactory
import org.apache.logging.log4j.core.config.ConfigurationSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ServiceLoggingTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `SPA terminal events reach both destinations without request or failure details`() {
        val terminalBytes = ByteArrayOutputStream()
        val logDirectory = directory.toRealPath().resolve("spa-http-events")
        val privacySentinels = listOf(
            "/private-path-sentinel",
            "private-query-sentinel",
            "http://private-origin-sentinel",
            "private-asset-name-sentinel.js",
            "private-exception-message-sentinel",
        )
        val previousError = System.err
        System.setErr(PrintStream(terminalBytes, true, Charsets.UTF_8))
        try {
            ServiceLogging.open(
                LoggingConfiguration(ServiceLogLevel.DEBUG, logDirectory),
                "svc_spa_http",
            ).use { session ->
                session.recorder.record(
                    BackendLogEvent.HttpRequestCompleted(
                        requestId = "req_spa_completed",
                        transport = "browser",
                        method = "GET",
                        operation = "spa_asset",
                        status = 200,
                        outcome = "spa_served",
                        durationMilliseconds = 4,
                        mutation = false,
                    ),
                )
                session.recorder.record(
                    BackendLogEvent.HttpRequestRejected(
                        requestId = "req_spa_rejected",
                        transport = "browser",
                        method = "HEAD",
                        operation = "spa_asset",
                        status = 404,
                        requestErrorCode = "ROUTE_NOT_FOUND",
                        durationMilliseconds = 5,
                    ),
                )
                session.recorder.record(
                    BackendLogEvent.HttpRequestFailed(
                        requestId = "req_spa_failed",
                        transport = "browser",
                        method = "GET",
                        operation = "spa_asset",
                        status = 500,
                        durationMilliseconds = 6,
                        failure = IllegalStateException(privacySentinels.joinToString(" ")),
                    ),
                )
            }
        } finally {
            System.setErr(previousError)
        }

        val terminal = terminalBytes.toString(Charsets.UTF_8)
        val jsonRecords = Files.readAllLines(logDirectory.resolve("bitbucket-helper.jsonl"))
            .map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(3, jsonRecords.size)
        listOf("http.request.completed", "http.request.rejected", "http.request.failed").forEach { eventName ->
            assertTrue(terminal.contains("event=$eventName"))
            assertTrue(jsonRecords.any { it.getValue("event").jsonPrimitive.content == eventName })
        }
        listOf("req_spa_completed", "req_spa_rejected", "req_spa_failed").forEach { requestId ->
            assertTrue(terminal.contains("request_id=$requestId"))
            assertTrue(jsonRecords.any { it.getValue("request_id").jsonPrimitive.content == requestId })
        }
        assertTrue(terminal.contains("operation=spa_asset"))
        assertTrue(terminal.contains("status=200"))
        assertTrue(terminal.contains("outcome=spa_served"))
        assertTrue(terminal.contains("request_error_code=ROUTE_NOT_FOUND"))
        assertTrue(terminal.contains("duration_ms=6"))
        assertTrue(jsonRecords.all { it.getValue("operation").jsonPrimitive.content == "spa_asset" })
        assertEquals("spa_served", jsonRecords[0].getValue("outcome").jsonPrimitive.content)
        assertEquals("ROUTE_NOT_FOUND", jsonRecords[1].getValue("request_error_code").jsonPrimitive.content)
        assertEquals(6L, jsonRecords[2].getValue("duration_ms").jsonPrimitive.long)
        privacySentinels.forEach { sentinel ->
            assertFalse(terminal.contains(sentinel))
            assertTrue(jsonRecords.none { it.toString().contains(sentinel) })
        }
    }

    @Test
    fun `classpath fallback is inert before service logging opens`() {
        val logDirectory = directory.toRealPath().resolve("inert-fallback")
        val captured = ByteArrayOutputStream()
        val previousError = System.err
        val propertyNames = listOf(
            "bitbucketHelper.logging.level",
            "bitbucketHelper.logging.directory",
            "bitbucketHelper.service.instance.id",
            "bitbucketHelper.logging.root.level",
        )
        val previousProperties = propertyNames.associateWith(System::getProperty)
        var isolatedContext: LoggerContext? = null
        try {
            System.setProperty("bitbucketHelper.logging.level", "OFF")
            System.setProperty("bitbucketHelper.logging.directory", logDirectory.toString())
            System.setProperty("bitbucketHelper.logging.root.level", "OFF")
            System.clearProperty("bitbucketHelper.service.instance.id")
            System.setErr(PrintStream(captured, true, Charsets.UTF_8))
            val fallbackContext = LoggerContext("inert-fallback")
            val source = ConfigurationSource.fromResource(
                "log4j2.xml",
                ServiceLoggingTest::class.java.classLoader,
            )
            val configuration = ConfigurationFactory.getInstance()
                .getConfiguration(fallbackContext, source)
            fallbackContext.start(configuration)
            isolatedContext = fallbackContext
            fallbackContext.getLogger(SAFE_TERMINAL_LOGGER_NAME)
                .atLevel(org.apache.logging.log4j.Level.DEBUG)
                .log("inert-fallback-sentinel")
            fallbackContext.getLogger(SAFE_STRUCTURED_LOGGER_NAME)
                .atLevel(org.apache.logging.log4j.Level.DEBUG)
                .log("inert-fallback-structured-sentinel")
        } finally {
            isolatedContext?.stop()
            previousProperties.forEach { (name, value) ->
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
            System.setErr(previousError)
        }

        assertEquals(0, captured.toString(Charsets.UTF_8).lineSequence().count { it.isNotBlank() })
        assertFalse(Files.exists(logDirectory))
        assertFalse(Files.exists(logDirectory.resolve("bitbucket-helper.jsonl")))
    }

    @Test
    fun `root framework and arbitrary application records never reach service appenders`() {
        val terminalBytes = ByteArrayOutputStream()
        val previousError = System.err
        val logDirectory = directory.toRealPath().resolve("framework-noise")
        val privateMessage = "private-framework-message-sentinel"
        System.setErr(PrintStream(terminalBytes, true, Charsets.UTF_8))
        try {
            ServiceLogging.open(
                LoggingConfiguration(ServiceLogLevel.DEBUG, logDirectory),
                "svc_framework_noise",
            ).use {
                val thrower = IllegalStateException(privateMessage)
                listOf(
                    LogManager.getRootLogger(),
                    LogManager.getLogger("io.ktor"),
                    LogManager.getLogger("org.jooq"),
                    LogManager.getLogger("org.quartz"),
                    LogManager.getLogger("liquibase"),
                    LogManager.getLogger("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated"),
                    LogManager.getLogger("com.mindtable.bitbuckethelper.arbitrary.application"),
                ).forEach { logger -> logger.error(privateMessage, thrower) }
            }
        } finally {
            System.setErr(previousError)
        }

        assertFalse(terminalBytes.toString(Charsets.UTF_8).contains(privateMessage))
        assertTrue(Files.readAllLines(logDirectory.resolve("bitbucket-helper.jsonl")).isEmpty())
    }

    @Test
    fun `debug event reaches terminal and json with typed fields`() {
        val terminalBytes = ByteArrayOutputStream()
        val terminal = PrintStream(terminalBytes, true, Charsets.UTF_8)
        val logDirectory = directory.toRealPath().resolve("logs")
        val jsonl = logDirectory.resolve("bitbucket-helper.jsonl")

        val previousError = System.err
        System.setErr(terminal)
        try {
            val session = ServiceLogging.open(
                LoggingConfiguration(ServiceLogLevel.DEBUG, logDirectory),
                "svc_test",
            )
            session.use {
                it.recorder.record(
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
        } finally {
            System.setErr(previousError)
        }

        val terminalText = terminalBytes.toString(Charsets.UTF_8)
        val terminalRecords = terminalText.lineSequence()
            .filter { it.contains("http.request.completed") }
            .toList()
        assertEquals(1, terminalRecords.size)
        assertTrue(terminalRecords.single().contains("request_id=req_test"))
        assertTrue(terminalRecords.single().contains("service_instance_id=svc_test"))
        assertTrue(Files.isRegularFile(jsonl))
        assertFalse(Files.isSymbolicLink(jsonl))
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(jsonl),
        )
        val event = Json.parseToJsonElement(Files.readAllLines(jsonl).single()).jsonObject
        assertTrue(event.getValue("timestamp").jsonPrimitive.content.endsWith("Z"))
        assertEquals("http.request.completed", event.getValue("event").jsonPrimitive.content)
        assertEquals("svc_test", event.getValue("service_instance_id").jsonPrimitive.content)
        val duration = event.getValue("duration_ms").jsonPrimitive
        assertFalse(duration.isString)
        assertEquals(7L, duration.long)
        val mutation = event.getValue("mutation").jsonPrimitive
        assertFalse(mutation.isString)
        assertEquals(false, mutation.boolean)
    }

    @Test
    fun `refresh failure summaries remain typed numbers and lists in both destinations`() {
        val terminalBytes = ByteArrayOutputStream()
        val previousError = System.err
        val logDirectory = directory.toRealPath().resolve("refresh-summary")
        System.setErr(PrintStream(terminalBytes, true, Charsets.UTF_8))
        try {
            ServiceLogging.open(
                LoggingConfiguration(ServiceLogLevel.DEBUG, logDirectory),
                "svc_refresh_summary",
            ).use { session ->
                session.recorder.record(
                    BackendLogEvent.RefreshRepositoryPartial(
                        refreshRunId = "rr_summary",
                        repositoryId = "repo_summary",
                        failureCategory = "authentication",
                        retryable = true,
                        durationMilliseconds = 12,
                        failureCount = 3,
                        failureCategories = listOf("authentication", "network"),
                    ),
                )
            }
        } finally {
            System.setErr(previousError)
        }

        val json = Json.parseToJsonElement(
            Files.readAllLines(logDirectory.resolve("bitbucket-helper.jsonl")).single(),
        ).jsonObject
        assertEquals(3L, json.getValue("failure_count").jsonPrimitive.long)
        assertEquals(
            listOf("authentication", "network"),
            json.getValue("failure_categories").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(terminalBytes.toString(Charsets.UTF_8).contains("failure_count=3"))
    }

    @Test
    fun `default terminal is the current standard error stream`() {
        val captured = ByteArrayOutputStream()
        val replacement = PrintStream(captured, true, Charsets.UTF_8)
        val previous = System.err
        val logDirectory = directory.toRealPath().resolve("default-terminal")
        System.setErr(replacement)
        try {
            ServiceLogging.open(
                LoggingConfiguration(ServiceLogLevel.DEBUG, logDirectory),
                "svc_default_terminal",
            ).use { session ->
                session.recorder.record(
                    BackendLogEvent.ServiceStarted(browserPort = 4312),
                )
            }
        } finally {
            System.setErr(previous)
        }
        assertTrue(captured.toString(Charsets.UTF_8).contains("service.started"))
    }

    @Test
    fun `failure event keeps only bounded diagnostics`() {
        val terminalBytes = ByteArrayOutputStream()
        val terminal = PrintStream(terminalBytes, true, Charsets.UTF_8)
        val logDirectory = directory.toRealPath().resolve("failure")
        val failure = IllegalStateException("private-message-sentinel")
        val previousError = System.err
        System.setErr(terminal)
        try {
            ServiceLogging.open(
                LoggingConfiguration(ServiceLogLevel.DEBUG, logDirectory),
                "svc_failure",
            ).use { session ->
                session.recorder.record(
                    BackendLogEvent.ServiceStartFailed("scheduler", failure),
                )
            }
        } finally {
            System.setErr(previousError)
        }

        val event = Json.parseToJsonElement(
            Files.readAllLines(logDirectory.resolve("bitbucket-helper.jsonl")).single(),
        ).jsonObject
        assertFalse(event.toString().contains("private-message-sentinel"))
        assertTrue(event.getValue("exception_types").jsonArray.isNotEmpty())
        assertFalse(event.getValue("diagnostic_truncated").jsonPrimitive.isString)
        assertFalse(terminalBytes.toString(Charsets.UTF_8).contains("private-message-sentinel"))
    }

    @Test
    fun `injected terminal does not duplicate the console appender record`() {
        val terminalBytes = ByteArrayOutputStream()
        val terminal = PrintStream(terminalBytes, true, Charsets.UTF_8)
        val logDirectory = directory.toRealPath().resolve("duplicate-terminal")
        val jsonl = logDirectory.resolve("bitbucket-helper.jsonl")

        val previousError = System.err
        System.setErr(terminal)
        try {
            val session = ServiceLogging.open(
                LoggingConfiguration(ServiceLogLevel.DEBUG, logDirectory),
                "svc_duplicate",
            )
            session.recorder.record(BackendLogEvent.ServiceStarted(browserPort = 4312))
            session.close()
            session.close()
        } finally {
            System.setErr(previousError)
        }

        val records = terminalBytes.toString(Charsets.UTF_8)
            .lineSequence()
            .filter { it.contains("service.started") }
            .toList()
        assertEquals(1, records.size)
        assertEquals(1, Files.readAllLines(jsonl).size)
    }

    @Test
    fun `rolling timestamps stay UTC when the default zone is not UTC`() {
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        val captured = ByteArrayOutputStream()
        val previousError = System.err
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        val logDirectory = directory.toRealPath().resolve("utc-rollover")
        try {
            ServiceLogging.open(
                LoggingConfiguration(ServiceLogLevel.DEBUG, logDirectory),
                "svc_utc",
            ).use { session ->
                session.recorder.record(BackendLogEvent.ServiceStarted(browserPort = 4312))
            }
        } finally {
            System.setErr(previousError)
            TimeZone.setDefault(previousZone)
        }

        val json = Json.parseToJsonElement(
            Files.readAllLines(logDirectory.resolve("bitbucket-helper.jsonl")).single(),
        ).jsonObject
        assertTrue(json.getValue("timestamp").jsonPrimitive.content.endsWith("Z"))
        val xml = ServiceLoggingTest::class.java.getResourceAsStream("/log4j2.xml")!!
            .bufferedReader().use { it.readText() }
        assertTrue(xml.contains("filePattern=\"\${logDirectory}/bitbucket-helper-%d{yyyy-MM-dd}{UTC}-%i.jsonl.gz\""))
    }

    @Test
    fun `adversarial frame escaping stays within the JSON diagnostic budget`() {
        val message = "adversarial-message-sentinel"
        val failure = IllegalStateException(message)
        val quotes = "\"".repeat(1000)
        val slashes = "\\".repeat(1000)
        val supplementary = "😀".repeat(1000)
        val adversarial = (quotes + slashes + "\u0000" + supplementary).repeat(1000)
        failure.stackTrace = Array(64) {
            StackTraceElement(adversarial, adversarial, adversarial, 42)
        }
        val logDirectory = directory.toRealPath().resolve("adversarial")
        val captured = ByteArrayOutputStream()
        val previousError = System.err
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try {
            ServiceLogging.open(
                LoggingConfiguration(ServiceLogLevel.DEBUG, logDirectory),
                "svc_adversarial",
            ).use { session ->
                session.recorder.record(BackendLogEvent.ServiceStartFailed("scheduler", failure))
            }
        } finally {
            System.setErr(previousError)
        }

        val event = Json.parseToJsonElement(
            Files.readAllLines(logDirectory.resolve("bitbucket-helper.jsonl")).single(),
        ).jsonObject
        val encodedDiagnostic = listOf("exception_types", "stack_trace", "diagnostic_truncated")
            .joinToString(separator = "") { key -> event.getValue(key).toString() }
        assertTrue(
            encodedDiagnostic.toByteArray(Charsets.UTF_8).size <= 32 * 1024,
            "encoded diagnostic bytes=${encodedDiagnostic.toByteArray(Charsets.UTF_8).size}",
        )
        assertTrue(event.getValue("diagnostic_truncated").jsonPrimitive.boolean)
        assertFalse(encodedDiagnostic.contains(message))
    }
}
