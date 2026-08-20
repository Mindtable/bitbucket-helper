package com.mindtable.bitbuckethelper.adapter.outbound.notification

import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryFailureCategory
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryKey
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryResult
import com.mindtable.bitbuckethelper.application.model.NotificationRequest
import com.mindtable.bitbuckethelper.application.model.NotificationSound
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.BackendLogLevel
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import com.mindtable.bitbuckethelper.support.FakeDesktopNotificationsExecutable
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
import java.nio.file.attribute.PosixFilePermission.GROUP_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OTHERS_READ
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopNotificationsProcessAdapterTest {
    @Test
    fun `records only provider completion metadata for an accepted notification`(@TempDir directory: Path) = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val executable = executable(directory, "printf '%s\\n' '{\"status\":\"accepted\"}'")

        val result = DesktopNotificationsProcessAdapter(
            executable = executable,
            recorder = BackendEventRecorder(events::add),
            timeSource = sequenceTimeSource(0L, 7_000_000L),
        ).send(
            request(
                deliveryKey = "delivery-secret",
                title = "title-secret",
                body = "body-secret",
                openUrl = URI("https://private.invalid/open-secret"),
                sound = NotificationSound.PING,
            ),
        )

        assertEquals(NotificationDeliveryResult.Accepted, result)
        assertEquals(
            listOf(BackendLogEvent.NotificationProviderCompleted(durationMilliseconds = 7)),
            events,
        )
        assertFalse(events.toString().contains("delivery-secret"))
        assertFalse(events.toString().contains("title-secret"))
        assertFalse(events.toString().contains("body-secret"))
        assertFalse(events.toString().contains("open-secret"))
    }

    @Test
    fun `records only safe category ambiguity and duration for a provider failure`(@TempDir directory: Path) = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val executable = executable(directory, "printf '%s\\n' '{\"status\":\"failed\",\"error\":{\"code\":\"delivery_failed\",\"message\":\"stderr-secret\"}}'; printf '%s' 'stdout-secret' >&2; exit 1")

        val result = DesktopNotificationsProcessAdapter(
            executable = executable,
            recorder = BackendEventRecorder(events::add),
            timeSource = sequenceTimeSource(0L, 7_000_000L),
        ).send(request())

        assertEquals(
            NotificationDeliveryResult.Failed(NotificationDeliveryFailureCategory.DELIVERY_FAILED, ambiguous = false),
            result,
        )
        assertEquals(
            listOf(
                BackendLogEvent.NotificationProviderFailed(
                    category = "delivery_failed",
                    ambiguous = false,
                    durationMilliseconds = 7,
                ),
            ),
            events,
        )
        assertFalse(events.toString().contains("stderr-secret"))
        assertFalse(events.toString().contains("stdout-secret"))
    }

    @Test
    fun `records a nonambiguous process-not-started failure without executable details`(@TempDir directory: Path) = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val executable = missingInterpreterExecutable(directory)

        val result = DesktopNotificationsProcessAdapter(
            executable = executable,
            recorder = BackendEventRecorder(events::add),
            timeSource = sequenceTimeSource(0L, 7_000_000L),
        ).send(request(deliveryKey = "delivery-secret", title = "title-secret", body = "body-secret"))

        assertEquals(
            failure(NotificationDeliveryFailureCategory.PROCESS_NOT_STARTED, ambiguous = false),
            result,
        )
        val event = events.single() as BackendLogEvent.NotificationProviderFailed
        assertEquals("process_not_started", event.category)
        assertFalse(event.ambiguous)
        assertEquals(7L, event.durationMilliseconds)
        assertEquals(BackendLogLevel.WARN, event.level)
        assertFalse(event.toString().contains(executable.toString()))
        assertFalse(event.toString().contains("delivery-secret"))
        assertFalse(event.toString().contains("title-secret"))
        assertFalse(event.toString().contains("body-secret"))
    }

    @Test
    fun `records malformed provider response without captured process data`(@TempDir directory: Path) = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val executable = executable(directory, "printf '%s\\n' '{\"status\":\"accepted\",\"private\":\"stdout-secret\"}' >&2; exit 0")

        val result = DesktopNotificationsProcessAdapter(
            executable = executable,
            recorder = BackendEventRecorder(events::add),
            timeSource = sequenceTimeSource(0L, 7_000_000L),
        ).send(request())

        assertEquals(failure(NotificationDeliveryFailureCategory.MALFORMED_RESPONSE, ambiguous = false), result)
        val event = events.single() as BackendLogEvent.NotificationProviderFailed
        assertEquals("malformed_response", event.category)
        assertFalse(event.ambiguous)
        assertEquals(7L, event.durationMilliseconds)
        assertFalse(event.toString().contains("stdout-secret"))
    }

    @Test
    fun `records unexpected exit and signal ambiguity as safe provider failures`(@TempDir directory: Path) = runBlocking {
        val cases = listOf(
            executable(directory, "exit 23") to failure(NotificationDeliveryFailureCategory.UNEXPECTED_EXIT, ambiguous = true),
            executable(directory, "kill -TERM ${'$'}${'$'}") to failure(NotificationDeliveryFailureCategory.AMBIGUOUS_PROCESS_FAILURE, ambiguous = true),
        )

        cases.forEach { (executable, expected) ->
            val events = mutableListOf<BackendLogEvent>()
            val result = DesktopNotificationsProcessAdapter(
                executable = executable,
                recorder = BackendEventRecorder(events::add),
                timeSource = sequenceTimeSource(0L, 7_000_000L),
            ).send(request())

            assertEquals(expected, result)
            val event = events.single() as BackendLogEvent.NotificationProviderFailed
            assertEquals(expected.category.name.lowercase(), event.category)
            assertEquals(expected.ambiguous, event.ambiguous)
            assertEquals(7L, event.durationMilliseconds)
            assertEquals(BackendLogLevel.WARN, event.level)
        }
    }

    @Test
    fun `throwing provider recorder cannot replace an accepted result`(@TempDir directory: Path) = runBlocking {
        var attempts = 0
        val recorder = BackendEventRecorder {
            attempts += 1
            throw IllegalStateException("provider-recorder-secret")
        }
        val result = DesktopNotificationsProcessAdapter(
            executable(directory, "printf '%s\\n' '{\"status\":\"accepted\"}'"),
            recorder = recorder,
            timeSource = sequenceTimeSource(0L, 7_000_000L),
        ).send(request())

        assertEquals(NotificationDeliveryResult.Accepted, result)
        assertEquals(1, attempts)
    }

    @Test
    fun `sends one direct argv in the provider contract order including URL and sound`(@TempDir directory: Path) = runBlocking {
        val argumentsFile = directory.resolve("arguments.txt")
        val executable = acceptedExecutable(directory, argumentsFile)
        val request = request(
            deliveryKey = "intent:alpha-7",
            title = "Release; ${'$'}(not-a-shell-command)",
            body = "Body with spaces & metacharacters",
            openUrl = URI("http://127.0.0.1:8080/inbox#item-7"),
            sound = NotificationSound.PING,
        )

        val result = DesktopNotificationsProcessAdapter(executable).send(request)

        assertEquals(NotificationDeliveryResult.Accepted, result)
        assertEquals(
            listOf(
                "send",
                "--delivery-key",
                "intent:alpha-7",
                "--title",
                "Release; ${'$'}(not-a-shell-command)",
                "--body",
                "Body with spaces & metacharacters",
                "--open-url",
                "http://127.0.0.1:8080/inbox#item-7",
                "--sound",
                "ping",
            ),
            Files.readAllLines(argumentsFile, UTF_8),
        )
    }

    @Test
    fun `sends every request sound as its canonical lowercase provider value`(@TempDir directory: Path) = runBlocking {
        NotificationSound.entries.forEach { sound ->
            val argumentsFile = directory.resolve("arguments-${sound.name}.txt")
            val executable = acceptedExecutable(directory, argumentsFile)

            val result = DesktopNotificationsProcessAdapter(executable).send(request(sound = sound))

            assertEquals(NotificationDeliveryResult.Accepted, result, sound.name)
            assertEquals(
                listOf(
                    "send",
                    "--delivery-key",
                    "intent:default",
                    "--title",
                    "Safe title",
                    "--body",
                    "Safe body",
                    "--sound",
                    PROVIDER_SOUNDS.getValue(sound),
                ),
                Files.readAllLines(argumentsFile, UTF_8),
                sound.name,
            )
        }
    }

    @Test
    fun `accepts only an accepted response with exit zero`(@TempDir directory: Path) = runBlocking {
        val executable = executable(directory, """
            printf '%s\n' '{"status":"accepted"}'
            exit 0
        """.trimIndent())

        assertEquals(NotificationDeliveryResult.Accepted, DesktopNotificationsProcessAdapter(executable).send(request()))
    }

    @Test
    fun `maps every provider failed response from exit one without exposing provider text`(@TempDir directory: Path) = runBlocking {
        providerFailureCases.forEach { case ->
            val executable = executable(directory, """
                printf '%s\n' '{"status":"failed","error":{"code":"${case.code}","message":"PROVIDER-SECRET-${case.code}"}}'
                exit 1
            """.trimIndent())

            val result = DesktopNotificationsProcessAdapter(executable).send(request())

            assertEquals(failure(case.category, ambiguous = false), result, case.code)
            assertFalse(result.toString().contains("PROVIDER-SECRET-${case.code}"), case.code)
        }
    }

    @Test
    fun `rejects every malformed strict response without exposing request or provider text`(@TempDir directory: Path) = runBlocking {
        malformedResponseCases.forEach { case ->
            val executable = executable(directory, case.script)
            val privateRequest = request(
                title = "TITLE-SECRET-${case.name}",
                body = "BODY-SECRET-${case.name}",
                openUrl = URI("https://private.invalid/${case.name}"),
            )

            val result = DesktopNotificationsProcessAdapter(executable).send(privateRequest)

            assertEquals(failure(NotificationDeliveryFailureCategory.MALFORMED_RESPONSE, ambiguous = false), result, case.name)
            assertFalse(result.toString().contains("TITLE-SECRET-${case.name}"), case.name)
            assertFalse(result.toString().contains("BODY-SECRET-${case.name}"), case.name)
            assertFalse(result.toString().contains("https://private.invalid/${case.name}"), case.name)
            assertFalse(result.toString().contains("PROVIDER-SECRET-${case.name}"), case.name)
        }
    }

    @Test
    fun `rejects accepted and failed result exit mismatches`(@TempDir directory: Path) = runBlocking {
        val cases = listOf(
            executable(directory, "printf '%s\\n' '{\"status\":\"accepted\"}'; exit 1"),
            executable(directory, "printf '%s\\n' '{\"status\":\"failed\",\"error\":{\"code\":\"delivery_failed\",\"message\":\"safe\"}}'; exit 0"),
        )

        cases.forEach { executable ->
            assertEquals(
                failure(NotificationDeliveryFailureCategory.UNEXPECTED_EXIT, ambiguous = false),
                DesktopNotificationsProcessAdapter(executable).send(request()),
            )
        }
    }

    @Test
    fun `classifies a nonprotocol raw exit as ambiguous unexpected exit`(@TempDir directory: Path) = runBlocking {
        val executable = executable(directory, "exit 23")

        assertEquals(
            failure(NotificationDeliveryFailureCategory.UNEXPECTED_EXIT, ambiguous = true),
            DesktopNotificationsProcessAdapter(executable).send(request()),
        )
    }

    @Test
    fun `classifies parseable accepted and failed JSON with abnormal exits as ambiguous`(@TempDir directory: Path) = runBlocking {
        val executables = listOf(
            executable(directory, "printf '%s\\n' '{\"status\":\"accepted\"}'; exit 23"),
            executable(directory, "printf '%s\\n' '{\"status\":\"failed\",\"error\":{\"code\":\"delivery_failed\",\"message\":\"safe\"}}'; exit 23"),
        )

        executables.forEach { executable ->
            assertEquals(
                failure(NotificationDeliveryFailureCategory.UNEXPECTED_EXIT, ambiguous = true),
                DesktopNotificationsProcessAdapter(executable).send(request()),
            )
        }
    }

    @Test
    fun `classifies literal and signal style 143 exits alike without claiming a signal cause`(@TempDir directory: Path) = runBlocking {
        val literalExit = executable(directory, "exit 143")
        val termExit = executable(directory, "kill -TERM ${'$'}${'$'}")

        listOf(literalExit, termExit).forEach { executable ->
            assertEquals(
                failure(NotificationDeliveryFailureCategory.AMBIGUOUS_PROCESS_FAILURE, ambiguous = true),
                DesktopNotificationsProcessAdapter(executable).send(request()),
            )
        }
    }

    @Test
    fun `rejects executable paths that are missing nonabsolute nonnormalized nonregular or nonexecutable`(@TempDir directory: Path) {
        val missing = directory.resolve("missing")
        val nonAbsolute = Path.of("relative-desktop-notifications")
        val regularExecutable = executable(directory, "exit 0")
        val nonNormalized = regularExecutable.parent.resolve(".").resolve(regularExecutable.fileName)
        val directoryPath = directory.resolve("directory").createDirectory()
        val nonExecutable = Files.createTempFile(directory, "not-executable-", ".sh")
        Files.writeString(nonExecutable, "#!/bin/sh\nexit 0\n", UTF_8)
        Files.setPosixFilePermissions(
            nonExecutable,
            setOf(OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ),
        )

        listOf(missing, nonAbsolute, nonNormalized, directoryPath, nonExecutable).forEach { invalidPath ->
            val exception = assertThrows(IllegalArgumentException::class.java) {
                DesktopNotificationsProcessAdapter(invalidPath)
            }
            assertFalse(exception.message.orEmpty().contains(invalidPath.toString()))
        }
    }

    @Test
    fun `classifies a valid executable that cannot start as nonambiguous not started`(@TempDir directory: Path) = runBlocking {
        val executable = missingInterpreterExecutable(directory)

        assertEquals(
            failure(NotificationDeliveryFailureCategory.PROCESS_NOT_STARTED, ambiguous = false),
            DesktopNotificationsProcessAdapter(executable).send(request()),
        )
    }

    @Test
    fun `classifies the fifteen second outer deadline as ambiguous delivery timeout`(@TempDir directory: Path) = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val executable = executable(directory, "while true; do sleep 1; done")
        val startedAt = System.nanoTime()

        val result = DesktopNotificationsProcessAdapter(
            executable = executable,
            recorder = BackendEventRecorder(events::add),
            timeSource = sequenceTimeSource(0L, 7_000_000L),
        ).send(request())
        val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0

        assertEquals(
            failure(NotificationDeliveryFailureCategory.DELIVERY_TIMEOUT, ambiguous = true),
            result,
        )
        assertEquals(
            listOf(BackendLogEvent.NotificationProviderFailed("delivery_timeout", ambiguous = true, durationMilliseconds = 7)),
            events,
        )
        assertTrue(elapsedSeconds >= 13.0, "outer deadline must remain close to fifteen seconds")
        assertTrue(elapsedSeconds < 18.0, "outer deadline must bound the invocation")
    }

    @Test
    fun `rethrows cancellation before process start without launching the executable`(@TempDir directory: Path) = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val startedFile = directory.resolve("started")
        val executable = executable(directory, "printf '%s' started > '$startedFile'")
        val cancellation = CompletableDeferred<CancellationException>()
        val job = launch {
            coroutineContext.cancel()
            try {
                DesktopNotificationsProcessAdapter(
                    executable = executable,
                    recorder = BackendEventRecorder(events::add),
                    timeSource = sequenceTimeSource(0L),
                ).send(request())
            } catch (exception: CancellationException) {
                cancellation.complete(exception)
            }
        }

        job.join()

        assertTrue(cancellation.isCompleted)
        assertFalse(startedFile.exists())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `classifies caller cancellation after the process starts as ambiguous`(@TempDir directory: Path) = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val readyFile = directory.resolve("ready")
        val executable = executable(directory, """
            printf '%s' ready > '$readyFile'
            while true; do sleep 1; done
        """.trimIndent())
        val result = CompletableDeferred<NotificationDeliveryResult>()
        val job = async {
            result.complete(
                DesktopNotificationsProcessAdapter(
                    executable = executable,
                    recorder = BackendEventRecorder(events::add),
                    timeSource = sequenceTimeSource(0L, 7_000_000L),
                ).send(request()),
            )
        }

        awaitFile(readyFile)
        job.cancelAndJoin()

        assertEquals(
            failure(NotificationDeliveryFailureCategory.AMBIGUOUS_PROCESS_FAILURE, ambiguous = true),
            withTimeout(5_000) { result.await() },
        )
        assertEquals(
            listOf(
                BackendLogEvent.NotificationProviderFailed(
                    category = "ambiguous_process_failure",
                    ambiguous = true,
                    durationMilliseconds = 7,
                ),
            ),
            events,
        )
    }

    private fun acceptedExecutable(directory: Path, argumentsFile: Path): Path = executable(directory, """
        printf '%s\n' "${'$'}@" > '$argumentsFile'
        printf '%s\n' '{"status":"accepted"}'
    """.trimIndent())

    private fun executable(directory: Path, script: String): Path =
        FakeDesktopNotificationsExecutable.create(directory, script)

    private fun missingInterpreterExecutable(directory: Path): Path {
        val executable = Files.createTempFile(directory, "missing-interpreter-", ".sh")
        Files.writeString(executable, "#!/definitely/missing/interpreter\n", UTF_8)
        Files.setPosixFilePermissions(
            executable,
            setOf(
                OWNER_READ,
                OWNER_WRITE,
                OWNER_EXECUTE,
                GROUP_READ,
                GROUP_EXECUTE,
                OTHERS_READ,
                OTHERS_EXECUTE,
            ),
        )
        return executable.toAbsolutePath().normalize()
    }

    private suspend fun awaitFile(path: Path) {
        withTimeout(5_000) {
            while (!path.exists()) delay(10)
        }
    }

    private fun request(
        deliveryKey: String = "intent:default",
        title: String = "Safe title",
        body: String = "Safe body",
        openUrl: URI? = null,
        sound: NotificationSound = NotificationSound.DEFAULT,
    ): NotificationRequest = NotificationRequest(
        deliveryKey = NotificationDeliveryKey(deliveryKey),
        title = title,
        body = body,
        openUrl = openUrl,
        sound = sound,
    )

    private fun failure(
        category: NotificationDeliveryFailureCategory,
        ambiguous: Boolean,
    ): NotificationDeliveryResult.Failed = NotificationDeliveryResult.Failed(category, ambiguous)

    private fun sequenceTimeSource(vararg values: Long): MonotonicTimeSource {
        val iterator = values.iterator()
        return MonotonicTimeSource { check(iterator.hasNext()) { "time source exhausted" }; iterator.nextLong() }
    }

    private data class ProviderFailureCase(
        val code: String,
        val category: NotificationDeliveryFailureCategory,
    )

    private data class MalformedResponseCase(
        val name: String,
        val script: String,
    )

    private companion object {
        val PROVIDER_SOUNDS = mapOf(
            NotificationSound.DEFAULT to "default",
            NotificationSound.BASSO to "basso",
            NotificationSound.BLOW to "blow",
            NotificationSound.BOTTLE to "bottle",
            NotificationSound.FROG to "frog",
            NotificationSound.FUNK to "funk",
            NotificationSound.GLASS to "glass",
            NotificationSound.HERO to "hero",
            NotificationSound.MORSE to "morse",
            NotificationSound.PING to "ping",
            NotificationSound.POP to "pop",
            NotificationSound.PURR to "purr",
            NotificationSound.SOS to "sosumi",
            NotificationSound.SUBMARINE to "submarine",
            NotificationSound.TINK to "tink",
        )

        val providerFailureCases = listOf(
            ProviderFailureCase("invalid_arguments", NotificationDeliveryFailureCategory.INVALID_ARGUMENTS),
            ProviderFailureCase("unsupported_platform", NotificationDeliveryFailureCategory.UNSUPPORTED_PLATFORM),
            ProviderFailureCase("dependency_unavailable", NotificationDeliveryFailureCategory.DEPENDENCY_UNAVAILABLE),
            ProviderFailureCase("delivery_timeout", NotificationDeliveryFailureCategory.DELIVERY_TIMEOUT),
            ProviderFailureCase("delivery_failed", NotificationDeliveryFailureCategory.DELIVERY_FAILED),
            ProviderFailureCase("internal_error", NotificationDeliveryFailureCategory.INTERNAL_ERROR),
        )

        val malformedResponseCases = listOf(
            MalformedResponseCase("empty", "exit 0"),
            MalformedResponseCase("multiple-json", "printf '%s' '{\"status\":\"accepted\"}\\n{\"status\":\"accepted\"}\\n'; exit 0"),
            MalformedResponseCase("bom", "printf '\\357\\273\\277{\"status\":\"accepted\"}\\n'; exit 0"),
            MalformedResponseCase("prefix", "printf '%s' 'prefix{\"status\":\"accepted\"}\\n'; exit 0"),
            MalformedResponseCase("suffix", "printf '%s' '{\"status\":\"accepted\"}suffix\\n'; exit 0"),
            MalformedResponseCase("missing-final-lf", "printf '%s' '{\"status\":\"accepted\"}'; exit 0"),
            MalformedResponseCase("crlf", "printf '%s' '{\"status\":\"accepted\"}\\r\\n'; exit 0"),
            MalformedResponseCase("multiple-final-lfs", "printf '%s' '{\"status\":\"accepted\"}\\n\\n'; exit 0"),
            MalformedResponseCase("leading-whitespace", "printf '%s' ' {\"status\":\"accepted\"}\\n'; exit 0"),
            MalformedResponseCase("trailing-whitespace", "printf '%s' '{\"status\":\"accepted\"} \\n'; exit 0"),
            MalformedResponseCase("unknown-response-member", "printf '%s\\n' '{\"status\":\"accepted\",\"trace\":\"PROVIDER-SECRET-unknown-response-member\"}'; exit 0"),
            MalformedResponseCase("unknown-error-member", "printf '%s\\n' '{\"status\":\"failed\",\"error\":{\"code\":\"delivery_failed\",\"message\":\"PROVIDER-SECRET-unknown-error-member\",\"trace\":\"extra\"}}'; exit 1"),
            MalformedResponseCase("unknown-code", "printf '%s\\n' '{\"status\":\"failed\",\"error\":{\"code\":\"unknown\",\"message\":\"PROVIDER-SECRET-unknown-code\"}}'; exit 1"),
            MalformedResponseCase("missing-failed-error", "printf '%s\\n' '{\"status\":\"failed\"}'; exit 1"),
            MalformedResponseCase("invalid-utf8", "printf '\\377'; exit 0"),
            MalformedResponseCase("stdout-overflow", "printf '%s' '{\"status\":\"accepted\"'; dd if=/dev/zero bs=65514 count=1 2>/dev/null | tr '\\000' ' '; printf '%s\\n' '}'; printf '%s' x; exit 0"),
        )
    }
}
