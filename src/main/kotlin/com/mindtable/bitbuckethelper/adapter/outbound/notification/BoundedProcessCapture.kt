package com.mindtable.bitbuckethelper.adapter.outbound.notification

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class BoundedProcessCapture(
    private val waitDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun capture(process: Process, timeout: Duration): NotificationProcessResult {
        require(!timeout.isNegative && !timeout.isZero) { "Process capture timeout must be positive" }

        val stdout = BoundedOutput(CAPTURE_LIMIT_BYTES)
        val stderr = BoundedOutput(CAPTURE_LIMIT_BYTES)
        val exitCode = withTimeoutOrNull(timeoutMillisCeiling(timeout)) {
            awaitExitAndDrain(process, stdout, stderr)
        }

        return if (exitCode == null) {
            NotificationProcessResult.TimedOut(
                stdout = stdout.bytes(),
                stderr = stderr.bytes(),
                stdoutOverflowed = stdout.overflowed,
                stderrOverflowed = stderr.overflowed,
            )
        } else {
            resultForExit(exitCode, stdout, stderr)
        }
    }

    private suspend fun awaitExitAndDrain(
        process: Process,
        stdout: BoundedOutput,
        stderr: BoundedOutput,
    ): Int = coroutineScope {
        val stdoutDrainer = async(Dispatchers.IO) { drain(process.inputStream, stdout) }
        val stderrDrainer = async(Dispatchers.IO) { drain(process.errorStream, stderr) }
        try {
            val exitCode = runInterruptible(waitDispatcher) { process.waitFor() }
            stdoutDrainer.await()
            stderrDrainer.await()
            exitCode
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                closeProcessStreams(process)
                if (process.isAlive) {
                    terminateAndReap(process)
                }
            }
            withContext(NonCancellable) {
                stdoutDrainer.join()
                stderrDrainer.join()
            }
        }
    }

    private fun resultForExit(
        exitCode: Int,
        stdout: BoundedOutput,
        stderr: BoundedOutput,
    ): NotificationProcessResult {
        val stdoutBytes = stdout.bytes()
        val stderrBytes = stderr.bytes()
        return NotificationProcessResult.Exited(
            exitCode = exitCode,
            derivedSignal = exitCode.takeIf { it in POSIX_SIGNAL_EXIT_CODES }
                ?.minus(POSIX_SIGNAL_EXIT_OFFSET),
            stdout = stdoutBytes,
            stderr = stderrBytes,
            stdoutOverflowed = stdout.overflowed,
            stderrOverflowed = stderr.overflowed,
        )
    }

    private fun closeProcessStreams(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun terminateAndReap(process: Process) {
        val parent = process.toHandle()
        val capturedDescendants = LinkedHashMap<Long, ProcessHandle>()

        refreshCapturedDescendants(parent, capturedDescendants)
        if (parent.isAlive) parent.destroy()
        waitForExit(listOf(parent), GRACEFUL_TERMINATION_NANOS)

        refreshCapturedDescendants(parent, capturedDescendants)
        requestTermination(capturedDescendants.values)
        waitForExit(capturedDescendants.values + parent, DESCENDANT_TERMINATION_NANOS)

        refreshCapturedDescendants(parent, capturedDescendants)
        forceTermination(capturedDescendants.values)
        if (parent.isAlive) parent.destroyForcibly()
        waitForExit(capturedDescendants.values + parent, FORCED_TERMINATION_NANOS)

        check(!parent.isAlive && capturedDescendants.values.none(ProcessHandle::isAlive)) {
            "Notification process cleanup failed"
        }
        check(process.waitFor(FORCED_REAP_SECONDS, TimeUnit.SECONDS)) {
            "Notification process reap failed"
        }
    }

    private fun captureDescendants(
        root: ProcessHandle,
        captured: MutableMap<Long, ProcessHandle>,
    ) {
        if (!root.isAlive) return
        root.descendants().use { descendants ->
            descendants.forEach { descendant -> captured.putIfAbsent(descendant.pid(), descendant) }
        }
    }

    private fun refreshCapturedDescendants(
        parent: ProcessHandle,
        captured: MutableMap<Long, ProcessHandle>,
    ) {
        captureDescendants(parent, captured)
        captured.values.toList().forEach { knownDescendant ->
            captureDescendants(knownDescendant, captured)
        }
    }

    private fun requestTermination(handles: Collection<ProcessHandle>) {
        handles.forEach { handle ->
            if (handle.isAlive) handle.destroy()
        }
    }

    private fun forceTermination(handles: Collection<ProcessHandle>) {
        handles.forEach { handle ->
            if (handle.isAlive) handle.destroyForcibly()
        }
    }

    private fun waitForExit(handles: Collection<ProcessHandle>, timeoutNanos: Long) {
        val deadline = System.nanoTime() + timeoutNanos
        while (handles.any(ProcessHandle::isAlive)) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return
            LockSupport.parkNanos(minOf(remaining, PROCESS_EXIT_POLL_NANOS))
        }
    }

    private fun drain(stream: InputStream, output: BoundedOutput) {
        stream.use { input ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return
                output.append(buffer, count)
            }
        }
    }

    private class BoundedOutput(private val limit: Int) {
        private val captured = ByteArrayOutputStream(limit)
        var overflowed: Boolean = false
            private set

        fun append(source: ByteArray, count: Int) {
            val remaining = limit - captured.size()
            val capturedCount = minOf(remaining, count)
            if (capturedCount > 0) captured.write(source, 0, capturedCount)
            if (count > capturedCount) overflowed = true
        }

        fun bytes(): ByteArray = captured.toByteArray()
    }

    companion object {
        internal fun timeoutMillisCeiling(timeout: Duration): Long {
            val truncatedMillis = timeout.toMillis()
            return if (timeout.nano % NANOS_PER_MILLISECOND == 0) {
                truncatedMillis
            } else {
                Math.addExact(truncatedMillis, 1L)
            }
        }

        private const val CAPTURE_LIMIT_BYTES = 65_536
        private const val READ_BUFFER_BYTES = 8_192
        private val GRACEFUL_TERMINATION_NANOS = TimeUnit.SECONDS.toNanos(1L)
        private val DESCENDANT_TERMINATION_NANOS = TimeUnit.MILLISECONDS.toNanos(100L)
        private val FORCED_TERMINATION_NANOS = TimeUnit.SECONDS.toNanos(1L)
        private const val FORCED_REAP_SECONDS = 1L
        private val PROCESS_EXIT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10L)
        private const val NANOS_PER_MILLISECOND = 1_000_000
        private const val POSIX_SIGNAL_EXIT_OFFSET = 128
        private val POSIX_SIGNAL_EXIT_CODES = 129..192
    }
}
