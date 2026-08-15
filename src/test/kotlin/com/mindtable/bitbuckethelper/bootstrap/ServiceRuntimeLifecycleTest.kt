package com.mindtable.bitbuckethelper.bootstrap

import java.lang.management.ManagementFactory
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

class ServiceRuntimeLifecycleTest {
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `close before start never resolves or binds the HTTP connector`(
        @TempDir directory: Path,
    ) = runBlocking {
        val runtime = ServiceRuntime.create(configuration(directory.resolve("closed.sqlite")))

        try {
            runtime.close()
            val portResolution = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.resolvedHttpPort()
            }
            try {
                assertFalse(
                    portResolution.isCompleted,
                    "closing an unstarted runtime must not start the lazy CIO server job",
                )
            } finally {
                portResolution.cancelAndJoin()
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `close waits for the complete start transition`(
        @TempDir directory: Path,
    ) {
        val startTransitionEntered = CountDownLatch(1)
        val releaseStartTransition = CountDownLatch(1)
        val runtime = ServiceRuntime.create(
            configuration = configuration(directory.resolve("interleaving.sqlite")),
            clock = FIXED_CLOCK,
            lifecycleProbe = ServiceRuntimeLifecycleProbe {
                startTransitionEntered.countDown()
                check(releaseStartTransition.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release the start transition"
                }
            },
        )
        val startFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()
        val startCompleted = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val startThread = Thread(
            {
                try {
                    runtime.start()
                } catch (failure: Throwable) {
                    startFailure.set(failure)
                } finally {
                    startCompleted.countDown()
                }
            },
            "service-runtime-start-test",
        )
        val closeThread = Thread(
            {
                try {
                    runtime.close()
                } catch (failure: Throwable) {
                    closeFailure.set(failure)
                } finally {
                    closeCompleted.countDown()
                }
            },
            "service-runtime-close-test",
        )

        try {
            startThread.start()
            assertTrue(startTransitionEntered.await(5, TimeUnit.SECONDS))
            closeThread.start()

            eventuallyWithin(Duration.ofSeconds(2)) {
                ManagementFactory.getThreadMXBean()
                    .getThreadInfo(closeThread.threadId())
                    ?.takeIf {
                        it.threadState == Thread.State.BLOCKED &&
                            it.lockOwnerId == startThread.threadId()
                    }
            }

            releaseStartTransition.countDown()
            assertTrue(startCompleted.await(5, TimeUnit.SECONDS))
            assertTrue(closeCompleted.await(5, TimeUnit.SECONDS))
            assertNull(startFailure.get())
            assertNull(closeFailure.get())
        } finally {
            releaseStartTransition.countDown()
            startThread.join(5_000)
            closeThread.join(5_000)
            runtime.close()
        }
    }

    private fun configuration(databasePath: Path) = ServiceConfiguration(
        httpHost = "127.0.0.1",
        httpPort = 0,
        databasePath = databasePath,
        refreshInterval = Duration.ofMinutes(15),
        bitbucketBaseUrl = URI("http://127.0.0.1:1/2.0"),
        bitbucketRequestTimeout = Duration.ofMillis(100),
        credentials = BitbucketCredentials("person@example.com", "test-token"),
    )

    private fun <T : Any> eventuallyWithin(timeout: Duration, condition: () -> T?): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            condition()?.let { return it }
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Condition was not satisfied before the monotonic deadline")
            }
            Thread.yield()
        }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-08-15T10:15:30Z"),
            ZoneOffset.UTC,
        )
    }
}
