package com.mindtable.bitbuckethelper.observability

import com.mindtable.bitbuckethelper.bootstrap.LoggingConfiguration
import com.mindtable.bitbuckethelper.bootstrap.ServiceLogLevel
import com.mindtable.bitbuckethelper.bootstrap.ServiceLogging
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ServiceLoggingTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `debug event reaches terminal and json with typed fields`() {
        val terminalBytes = ByteArrayOutputStream()
        val terminal = PrintStream(terminalBytes, true, Charsets.UTF_8)
        val logDirectory = directory.toRealPath().resolve("logs")
        val jsonl = logDirectory.resolve("bitbucket-helper.jsonl")

        val session = ServiceLogging.open(
            LoggingConfiguration(ServiceLogLevel.DEBUG, logDirectory),
            "svc_test",
            terminal,
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

        val terminalText = terminalBytes.toString(Charsets.UTF_8)
        assertTrue(terminalText.contains("http.request.completed"))
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
        assertEquals(7L, event.getValue("duration_ms").jsonPrimitive.long)
        assertEquals(false, event.getValue("mutation").jsonPrimitive.boolean)
        assertFalse(event.getValue("mutation").jsonPrimitive.isString)
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
        ServiceLogging.open(
            LoggingConfiguration(ServiceLogLevel.DEBUG, logDirectory),
            "svc_failure",
            terminal,
        ).use { session ->
            session.recorder.record(
                BackendLogEvent.ServiceStartFailed("scheduler", failure),
            )
        }

        val event = Json.parseToJsonElement(
            Files.readAllLines(logDirectory.resolve("bitbucket-helper.jsonl")).single(),
        ).jsonObject
        assertFalse(event.toString().contains("private-message-sentinel"))
        assertTrue(event.getValue("exception_types").jsonArray.isNotEmpty())
        assertFalse(event.getValue("diagnostic_truncated").jsonPrimitive.isString)
        assertFalse(terminalBytes.toString(Charsets.UTF_8).contains("private-message-sentinel"))
    }
}
