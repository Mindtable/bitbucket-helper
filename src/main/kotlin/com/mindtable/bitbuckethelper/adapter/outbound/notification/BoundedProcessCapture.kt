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
        private const val GRACEFUL_TERMINATION_SECONDS = 1L
        private const val NANOS_PER_MILLISECOND = 1_000_000
        private const val POSIX_SIGNAL_EXIT_OFFSET = 128
        private val POSIX_SIGNAL_EXIT_CODES = 129..192
    }
}
