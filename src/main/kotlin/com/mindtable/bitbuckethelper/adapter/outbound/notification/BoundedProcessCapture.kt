package com.mindtable.bitbuckethelper.adapter.outbound.notification

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class BoundedProcessCapture(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun capture(process: Process, timeout: Duration): NotificationProcessResult {
        require(!timeout.isNegative && !timeout.isZero) { "Process capture timeout must be positive" }

        val stdout = BoundedOutput(CAPTURE_LIMIT_BYTES)
        val stderr = BoundedOutput(CAPTURE_LIMIT_BYTES)
        val exitCode = withTimeoutOrNull(timeout.toMillis().coerceAtLeast(MINIMUM_TIMEOUT_MILLIS)) {
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
        val stdoutDrainer = async(ioDispatcher) { drain(process.inputStream, stdout) }
        val stderrDrainer = async(ioDispatcher) { drain(process.errorStream, stderr) }
        try {
            val exitCode = runInterruptible(ioDispatcher) { process.waitFor() }
            stdoutDrainer.await()
            stderrDrainer.await()
            exitCode
        } finally {
            withContext(NonCancellable + ioDispatcher) {
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
        process.destroy()
        if (!process.waitFor(GRACEFUL_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
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

    private companion object {
        const val CAPTURE_LIMIT_BYTES = 65_536
        const val READ_BUFFER_BYTES = 8_192
        const val GRACEFUL_TERMINATION_SECONDS = 1L
        const val MINIMUM_TIMEOUT_MILLIS = 1L
        const val POSIX_SIGNAL_EXIT_OFFSET = 128
        val POSIX_SIGNAL_EXIT_CODES = 129..192
    }
}
