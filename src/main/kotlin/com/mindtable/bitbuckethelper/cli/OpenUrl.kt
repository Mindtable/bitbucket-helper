package com.mindtable.bitbuckethelper.cli

import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Browser-launch boundary; command tests replace it with a non-GUI fake. */
fun interface OpenUrl {
    fun open(url: String): Boolean
}

/** Starts an OS process without exposing a shell-interpreted command string. */
fun interface ProcessStarter {
    fun start(command: List<String>): Process
}

/** macOS implementation that launches one URL through the system open utility. */
class MacOsOpenUrl(
    private val processStarter: ProcessStarter = ProcessStarter { command -> ProcessBuilder(command).start() },
    private val waitTimeout: Duration = DEFAULT_WAIT_TIMEOUT,
) : OpenUrl {
    init {
        require(!waitTimeout.isZero && !waitTimeout.isNegative) { "waitTimeout must be positive" }
    }

    override fun open(url: String): Boolean {
        var process: Process? = null
        return try {
            process = processStarter.start(listOf(OPEN_EXECUTABLE, url))
            process.waitFor(waitTimeout.toMillis(), TimeUnit.MILLISECONDS) && process.exitValue() == 0
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalThreadStateException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            process?.cleanup()
        }
    }

    private fun Process.cleanup() {
        closeChildStreams()
        val wasAlive = try {
            isAlive
        } catch (_: SecurityException) {
            false
        }
        if (!wasAlive) return
        try {
            destroyForcibly()
        } catch (_: SecurityException) {
            return
        }
        try {
            waitFor(POST_DESTROY_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun Process.closeChildStreams() {
        listOf(outputStream, inputStream, errorStream).forEach { stream ->
            try {
                stream.close()
            } catch (_: IOException) {
                // Best-effort cleanup; command success is determined by the child exit status.
            }
        }
    }

    private companion object {
        const val OPEN_EXECUTABLE = "/usr/bin/open"
        val DEFAULT_WAIT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val POST_DESTROY_WAIT_TIMEOUT: Duration = Duration.ofMillis(100)
    }
}
