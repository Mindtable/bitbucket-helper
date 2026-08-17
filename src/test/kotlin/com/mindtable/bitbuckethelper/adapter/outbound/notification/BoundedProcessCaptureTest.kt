package com.mindtable.bitbuckethelper.adapter.outbound.notification

import com.mindtable.bitbuckethelper.support.FakeDesktopNotificationsExecutable
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BoundedProcessCaptureTest {
    @Test
    fun `timeout conversion does not shorten fractional milliseconds`() {
        assertEquals(2L, BoundedProcessCapture.timeoutMillisCeiling(Duration.ofNanos(1_900_000)))
    }

    @Test
    fun `capture retains stdout produced before stderr and preserves natural exit`(@TempDir directory: Path) = runBlocking {
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            """
            printf '%s' 'stdout-first'
            sleep 1
            printf '%s' 'stderr-after' >&2
            exit 23
            """.trimIndent(),
        )

        withStartedProcess(executable) { process ->
            val result = BoundedProcessCapture().capture(process, Duration.ofSeconds(3))

            assertEquals("stdout-first", String(result.stdout, UTF_8))
            assertEquals("stderr-after", String(result.stderr, UTF_8))
            assertFalse(result.stdoutOverflowed)
            assertFalse(result.stderrOverflowed)
            assertExited(result, 23)
            assertReaped(process)
        }
    }

    @Test
    fun `capture retains stderr produced before stdout and preserves natural exit`(@TempDir directory: Path) = runBlocking {
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            """
            printf '%s' 'stderr-first' >&2
            sleep 1
            printf '%s' 'stdout-after'
            exit 24
            """.trimIndent(),
        )

        withStartedProcess(executable) { process ->
            val result = BoundedProcessCapture().capture(process, Duration.ofSeconds(3))

            assertEquals("stdout-after", String(result.stdout, UTF_8))
            assertEquals("stderr-first", String(result.stderr, UTF_8))
            assertExited(result, 24)
            assertReaped(process)
        }
    }

    @Test
    fun `capture drains both streams larger than pipe buffers without deadlock`(@TempDir directory: Path) = runBlocking {
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            concurrentlyWrite(STREAM_LARGER_THAN_PIPE, STREAM_LARGER_THAN_PIPE, 25),
        )

        withStartedProcess(executable) { process ->
            val result = BoundedProcessCapture().capture(process, Duration.ofSeconds(5))

            assertEquals(CAPTURE_LIMIT, result.stdout.size)
            assertEquals(CAPTURE_LIMIT, result.stderr.size)
            assertTrue(result.stdoutOverflowed)
            assertTrue(result.stderrOverflowed)
            assertTrue(result.stdout.all { it == 'o'.code.toByte() })
            assertTrue(result.stderr.all { it == 'e'.code.toByte() })
            assertExited(result, 25)
            assertReaped(process)
        }
    }

    @Test
    fun `timeout cleanup is not starved by a single thread blocking dispatcher`(@TempDir directory: Path) {
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            concurrentlyWrite(STREAM_LARGER_THAN_PIPE, STREAM_LARGER_THAN_PIPE, 26),
        )
        val process = ProcessBuilder(listOf(executable.toString())).start()
        val singleThreadDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                runBlocking {
                    val result = BoundedProcessCapture(singleThreadDispatcher)
                        .capture(process, Duration.ofMillis(100))

                    assertTimedOut(result)
                }
            }
            assertReaped(process)
        } finally {
            stopIfAlive(process)
            singleThreadDispatcher.close()
        }
    }

    @Test
    fun `capture accepts exactly 65536 bytes on each stream without overflow`(@TempDir directory: Path) = runBlocking {
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            """
            printf '%s' '${"o".repeat(CAPTURE_LIMIT)}'
            printf '%s' '${"e".repeat(CAPTURE_LIMIT)}' >&2
            exit 29
            """.trimIndent(),
        )

        withStartedProcess(executable) { process ->
            val result = BoundedProcessCapture().capture(process, Duration.ofSeconds(5))

            assertEquals(CAPTURE_LIMIT, result.stdout.size)
            assertEquals(CAPTURE_LIMIT, result.stderr.size)
            assertFalse(result.stdoutOverflowed)
            assertFalse(result.stderrOverflowed)
            assertExited(result, 29)
            assertReaped(process)
        }
    }

    @Test
    fun `capture caps overflowed stream bytes and continues draining until natural exit`(@TempDir directory: Path) = runBlocking {
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            concurrentlyWrite(CAPTURE_LIMIT + 1, CAPTURE_LIMIT + 7, 31),
        )

        withStartedProcess(executable) { process ->
            val result = BoundedProcessCapture().capture(process, Duration.ofSeconds(5))

            assertEquals(CAPTURE_LIMIT, result.stdout.size)
            assertEquals(CAPTURE_LIMIT, result.stderr.size)
            assertTrue(result.stdoutOverflowed)
            assertTrue(result.stderrOverflowed)
            assertExited(result, 31)
            assertReaped(process)
        }
    }

    @Test
    fun `capture preserves a POSIX signal exit as a raw exit with a derived signal`(@TempDir directory: Path) = runBlocking {
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            """
            printf '%s' 'before-signal'
            printf '%s' 'signal-diagnostic' >&2
            kill -TERM $$
            """.trimIndent(),
        )

        withStartedProcess(executable) { process ->
            val result = BoundedProcessCapture().capture(process, Duration.ofSeconds(3))

            assertEquals("before-signal", String(result.stdout, UTF_8))
            assertEquals("signal-diagnostic", String(result.stderr, UTF_8))
            assertExitedWithDerivedSignal(result, 143, 15)
            assertReaped(process)
        }
    }

    @Test
    fun `capture preserves natural exit 143 as a raw exit with an ambiguous derived signal`(@TempDir directory: Path) = runBlocking {
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            "exit 143",
        )

        withStartedProcess(executable) { process ->
            val result = BoundedProcessCapture().capture(process, Duration.ofSeconds(3))

            assertExitedWithDerivedSignal(result, 143, 15)
            assertReaped(process)
        }
    }

    @Test
    fun `capture classifies outer timeout and reaps the child`(@TempDir directory: Path) = runBlocking {
        val ready = directory.resolve("timeout-ready")
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            waitingForTerminationScript("printf '%s' ready > \"${'$'}READY_FILE\""),
        )

        withStartedProcess(executable, mapOf("READY_FILE" to ready.toString())) { process ->
            awaitFile(ready)
            val result = BoundedProcessCapture().capture(process, Duration.ofMillis(100))

            assertTimedOut(result)
            assertReaped(process)
        }
    }

    @Test
    fun `timeout reaps the captured parent and its long lived descendant`(@TempDir directory: Path) = runBlocking {
        val parentPidFile = directory.resolve("tree-parent-pid")
        val childPidFile = directory.resolve("tree-child-pid")
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            processTreeScript(parentPidFile, childPidFile),
        )
        val process = ProcessBuilder(listOf(executable.toString())).start()

        try {
            val parentPid = awaitPid(parentPidFile)
            val childPid = awaitPid(childPidFile)

            val result = BoundedProcessCapture().capture(process, Duration.ofMillis(100))

            assertTimedOut(result)
            awaitDead(parentPid)
            awaitDead(childPid)
            assertReaped(process)
        } finally {
            stopIfAlive(process)
            stopPidIfAlive(parentPidFile)
            stopPidIfAlive(childPidFile)
        }
    }

    @Test
    fun `capture cancellation error excludes captured stdout and stderr bytes`(@TempDir directory: Path) = runBlocking {
        val ready = directory.resolve("error-message-ready")
        val stdoutMarker = "stdout-sensitive-marker"
        val stderrMarker = "stderr-sensitive-marker"
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            waitingForTerminationScript(
                """
                printf '%s' '$stdoutMarker'
                printf '%s' '$stderrMarker' >&2
                printf '%s' ready > "${'$'}READY_FILE"
                """.trimIndent(),
            ),
        )

        withStartedProcess(executable, mapOf("READY_FILE" to ready.toString())) { process ->
            awaitFile(ready)
            val failure = runCatching {
                withTimeout(100) {
                    BoundedProcessCapture().capture(process, Duration.ofSeconds(10))
                }
            }.exceptionOrNull()

            assertTrue(failure is TimeoutCancellationException)
            assertFalse(failure?.message.orEmpty().contains(stdoutMarker))
            assertFalse(failure?.message.orEmpty().contains(stderrMarker))
            assertReaped(process)
        }
    }

    @Test
    fun `capture rounds a positive submillisecond timeout up and reaps the child`(@TempDir directory: Path) = runBlocking {
        val ready = directory.resolve("submillisecond-ready")
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            waitingForTerminationScript("printf '%s' ready > \"${'$'}READY_FILE\""),
        )

        withStartedProcess(executable, mapOf("READY_FILE" to ready.toString())) { process ->
            awaitFile(ready)
            val result = BoundedProcessCapture().capture(process, Duration.ofNanos(1))

            assertTimedOut(result)
            assertReaped(process)
        }
    }

    @Test
    fun `capture completes after an exited parent leaves a descendant holding inherited pipes`(@TempDir directory: Path) {
        val descendantPid = directory.resolve("descendant-pid")
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            """
            sleep 30 &
            printf '%s' "${'$'}!" > "${'$'}DESCENDANT_PID_FILE"
            exit 0
            """.trimIndent(),
        )
        val process = ProcessBuilder(listOf(executable.toString()))
            .apply { environment()["DESCENDANT_PID_FILE"] = descendantPid.toString() }
            .start()

        try {
            runBlocking { awaitFile(descendantPid) }
            val spawnedDescendant = Files.readString(descendantPid, UTF_8).trim().toLong()
            assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                runBlocking {
                    val startedAt = System.nanoTime()
                    val result = BoundedProcessCapture().capture(process, Duration.ofMillis(100))
                    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

                    assertExited(result, 0)
                    assertTrue(elapsedMillis < 1_500, "capture must not wait for the descendant pipe owner")
                    assertReaped(process)
                    assertTrue(ProcessHandle.of(spawnedDescendant).orElseThrow().isAlive)
                }
            }
        } finally {
            stopIfAlive(process)
            stopDescendantIfPresent(descendantPid)
        }
    }

    @Test
    fun `timeout uses graceful destroy before force and reaps the child`(@TempDir directory: Path) = runBlocking {
        val ready = directory.resolve("graceful-ready")
        val terminated = directory.resolve("graceful-terminated")
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            """
            printf '%s' ready > "${'$'}READY_FILE"
            trap 'printf "%s" terminated > "${'$'}TERMINATED_FILE"; exit 0' TERM
            while :; do sleep 1; done
            """.trimIndent(),
        )

        withStartedProcess(
            executable,
            mapOf("READY_FILE" to ready.toString(), "TERMINATED_FILE" to terminated.toString()),
        ) { process ->
            awaitFile(ready)
            val result = BoundedProcessCapture().capture(process, Duration.ofMillis(100))

            assertTimedOut(result)
            awaitFile(terminated)
            assertEquals("terminated", Files.readString(terminated, UTF_8))
            assertReaped(process)
        }
    }

    @Test
    fun `timeout forces a child that ignores graceful destroy after one second and reaps it`(@TempDir directory: Path) = runBlocking {
        val ready = directory.resolve("forced-ready")
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            """
            printf '%s' ready > "${'$'}READY_FILE"
            trap '' TERM
            while :; do sleep 1; done
            """.trimIndent(),
        )

        withStartedProcess(executable, mapOf("READY_FILE" to ready.toString())) { process ->
            awaitFile(ready)
            val startedAt = System.nanoTime()
            val result = BoundedProcessCapture().capture(process, Duration.ofMillis(100))
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTimedOut(result)
            assertTrue(elapsedMillis >= 900, "forced cleanup must first allow one second for graceful destroy")
            assertTrue(elapsedMillis < 1_800, "forced cleanup must not extend the one-second grace period")
            assertReaped(process)
        }
    }

    @Test
    fun `cancellation after process start destroys and reaps the child`(@TempDir directory: Path) = runBlocking {
        val ready = directory.resolve("cancellation-ready")
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            waitingForTerminationScript("printf '%s' ready > \"${'$'}READY_FILE\""),
        )

        withStartedProcess(executable, mapOf("READY_FILE" to ready.toString())) { process ->
            awaitFile(ready)
            val capture = async(Dispatchers.Default) {
                BoundedProcessCapture().capture(process, Duration.ofSeconds(10))
            }

            delay(100)
            capture.cancelAndJoin()

            assertTrue(capture.isCancelled)
            assertReaped(process)
        }
    }

    @Test
    fun `cancellation forces a child that ignores graceful destroy after one second and reaps it`(@TempDir directory: Path) = runBlocking {
        val ready = directory.resolve("cancellation-forced-ready")
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            """
            printf '%s' ready > "${'$'}READY_FILE"
            trap '' TERM
            while :; do sleep 1; done
            """.trimIndent(),
        )

        withStartedProcess(executable, mapOf("READY_FILE" to ready.toString())) { process ->
            awaitFile(ready)
            val capture = async(Dispatchers.Default) {
                BoundedProcessCapture().capture(process, Duration.ofSeconds(10))
            }

            delay(100)
            val startedAt = System.nanoTime()
            capture.cancelAndJoin()
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue(capture.isCancelled)
            assertTrue(elapsedMillis >= 900, "cancellation must first allow one second for graceful destroy")
            assertTrue(elapsedMillis < 1_800, "cancellation must not extend the one-second grace period")
            assertReaped(process)
        }
    }

    private fun concurrentlyWrite(stdoutBytes: Int, stderrBytes: Int, exitCode: Int): String =
        """
        (printf '%s' '${"o".repeat(stdoutBytes)}') &
        (printf '%s' '${"e".repeat(stderrBytes)}' >&2) &
        wait
        exit $exitCode
        """.trimIndent()

    private fun waitingForTerminationScript(ready: String): String =
        """
        $ready
        trap 'exit 0' TERM
        while :; do sleep 1; done
        """.trimIndent()

    private fun processTreeScript(parentPidFile: Path, childPidFile: Path): String =
        """
        sleep 60 &
        child_pid="${'$'}!"
        printf '%s' "${'$'}${'$'}" > '$parentPidFile'
        printf '%s' "${'$'}child_pid" > '$childPidFile'
        wait "${'$'}child_pid"
        """.trimIndent()

    private suspend fun awaitFile(path: Path) {
        withTimeout(2_000) {
            while (!Files.exists(path)) delay(10)
        }
    }

    private suspend fun awaitPid(path: Path): Long = withTimeout(2_000) {
        while (!Files.exists(path) || Files.readString(path, UTF_8).isBlank()) delay(10)
        Files.readString(path, UTF_8).trim().toLong()
    }

    private suspend fun awaitDead(pid: Long) = withTimeout(2_000) {
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10)
    }

    private suspend fun <T> withStartedProcess(
        executable: Path,
        environment: Map<String, String> = emptyMap(),
        block: suspend (Process) -> T,
    ): T {
        val process = ProcessBuilder(listOf(executable.toString()))
            .apply { this.environment().putAll(environment) }
            .start()
        return try {
            block(process)
        } finally {
            stopIfAlive(process)
        }
    }

    private fun stopIfAlive(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(2, TimeUnit.SECONDS)) { "Test fixture did not terminate" }
        }
    }

    private fun stopDescendantIfPresent(pidPath: Path) {
        if (!Files.exists(pidPath)) return
        val pid = Files.readString(pidPath, UTF_8).trim().toLong()
        val descendant = ProcessHandle.of(pid).orElse(null) ?: return
        descendant.destroyForcibly()
        descendant.onExit().get(2, TimeUnit.SECONDS)
    }

    private fun stopPidIfAlive(pidPath: Path) {
        if (!Files.exists(pidPath)) return
        val text = Files.readString(pidPath, UTF_8).trim()
        if (text.isEmpty()) return
        val handle = ProcessHandle.of(text.toLong()).orElse(null) ?: return
        if (handle.isAlive) handle.destroyForcibly()
        handle.onExit().get(2, TimeUnit.SECONDS)
    }

    private fun assertExited(result: NotificationProcessResult, exitCode: Int) {
        assertTrue(result is NotificationProcessResult.Exited)
        assertEquals(exitCode, (result as NotificationProcessResult.Exited).exitCode)
    }

    private fun assertExitedWithDerivedSignal(result: NotificationProcessResult, exitCode: Int, signal: Int) {
        assertTrue(result is NotificationProcessResult.Exited)
        result as NotificationProcessResult.Exited
        assertEquals(exitCode, result.exitCode)
        assertEquals(signal, result.derivedSignal)
    }

    private fun assertTimedOut(result: NotificationProcessResult) {
        assertTrue(result is NotificationProcessResult.TimedOut)
    }

    private fun assertReaped(process: Process) {
        assertFalse(process.isAlive)
        assertTrue(ProcessHandle.of(process.pid()).isEmpty)
    }

    private companion object {
        const val CAPTURE_LIMIT = 65_536
        const val STREAM_LARGER_THAN_PIPE = 262_144
    }
}
