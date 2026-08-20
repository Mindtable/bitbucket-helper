package com.mindtable.bitbuckethelper.bootstrap

import com.github.ajalt.clikt.testing.test
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEventRecorder
import com.mindtable.bitbuckethelper.cli.ProductCommandDependencies
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

class ServiceLoggingBootstrapTest {
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `shutdown during runtime start waits for started event before stopping`(
        @TempDir directory: Path,
    ) {
        val events = mutableListOf<BackendLogEvent>()
        val order = mutableListOf<String>()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val shutdownRequested = CountDownLatch(1)
        val hook = AtomicReference<Thread>()
        val runtimeCloseCalls = AtomicInteger()
        val session = recordingSession(events) { order += "logging.close" }
        val runtime = ServiceRuntime.createForLifecycleTest(
            RuntimeLifecycleActions(
                startScheduler = {
                    startEntered.countDown()
                    check(releaseStart.await(5, TimeUnit.SECONDS))
                },
                startServers = { 18443 },
                stopServers = { order += "servers.stop" },
                stopScheduler = { runtimeCloseCalls.incrementAndGet() },
                cancelAndJoinScope = {},
                closeGateway = {},
                closePersistence = {},
            ),
            session.recorder,
        )
        val configuration = configuration(directory)
        val seams = ServiceBootstrapSeams(
            config = configuration.config,
            serviceInstanceIdSource = { "svc_start_interleave" },
            openLogging = { _, _ -> session },
            createRuntime = { _, _, _, _ -> runtime },
            installShutdownHook = { shutdownHook ->
                hook.set(shutdownHook)
            },
            removeShutdownHook = {},
            onShutdownRequested = { shutdownRequested.countDown() },
        )
        val serviceFailure = AtomicReference<Throwable?>()
        val serviceCompleted = CountDownLatch(1)
        val serviceThread = Thread(
            {
                try {
                    runConfiguredService(
                        environment = configuration.environment,
                        seams = seams,
                    )
                } catch (failure: Throwable) {
                    serviceFailure.set(failure)
                } finally {
                    serviceCompleted.countDown()
                }
            },
            "service-bootstrap-start-interleave-test",
        )

        serviceThread.start()
        try {
            assertTrue(startEntered.await(5, TimeUnit.SECONDS))
            val capturedHook = eventuallyWithin(Duration.ofSeconds(2)) { hook.get() }
            Thread { capturedHook.run() }.start()
            assertTrue(shutdownRequested.await(5, TimeUnit.SECONDS))
            releaseStart.countDown()
            assertTrue(serviceCompleted.await(5, TimeUnit.SECONDS))
            assertSame(null, serviceFailure.get())
            assertEquals(1, runtimeCloseCalls.get())
            assertEquals(
                listOf(
                    "service.starting",
                    "service.started",
                    "service.stopping",
                    "service.stopped",
                ),
                events.map(BackendLogEvent::eventName),
            )
            assertEquals("logging.close", order.last())
        } finally {
            releaseStart.countDown()
            serviceThread.join(5_000)
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `shutdown during port resolution waits for service started before cleanup`(
        @TempDir directory: Path,
    ) {
        val events = mutableListOf<BackendLogEvent>()
        val order = mutableListOf<String>()
        val portEntered = CountDownLatch(1)
        val releasePort = CountDownLatch(1)
        val shutdownRequested = CountDownLatch(1)
        val hook = AtomicReference<Thread>()
        val runtimeCloseCalls = AtomicInteger()
        val session = recordingSession(events) { order += "logging.close" }
        val runtime = ServiceRuntime.createForLifecycleTest(
            RuntimeLifecycleActions(
                startScheduler = {},
                startServers = { 18443 },
                stopServers = {},
                stopScheduler = { runtimeCloseCalls.incrementAndGet() },
                cancelAndJoinScope = {},
                closeGateway = {},
                closePersistence = {},
            ),
        )
        val configuration = configuration(directory)
        val seams = ServiceBootstrapSeams(
            config = configuration.config,
            serviceInstanceIdSource = { "svc_port_interleave" },
            openLogging = { _, _ -> session },
            createRuntime = { _, _, _, _ -> runtime },
            installShutdownHook = { shutdownHook -> hook.set(shutdownHook) },
            removeShutdownHook = {},
            onShutdownRequested = { shutdownRequested.countDown() },
            resolveBrowserPort = {
                portEntered.countDown()
                check(releasePort.await(5, TimeUnit.SECONDS))
                18443
            },
        )
        val serviceFailure = AtomicReference<Throwable?>()
        val serviceCompleted = CountDownLatch(1)
        val serviceThread = Thread(
            {
                try {
                    runConfiguredService(configuration.environment, seams)
                } catch (failure: Throwable) {
                    serviceFailure.set(failure)
                } finally {
                    serviceCompleted.countDown()
                }
            },
            "service-bootstrap-port-interleave-test",
        )

        serviceThread.start()
        try {
            assertTrue(portEntered.await(5, TimeUnit.SECONDS))
            val capturedHook = eventuallyWithin(Duration.ofSeconds(2)) { hook.get() }
            val hookThread = Thread(capturedHook::run)
            hookThread.start()
            assertTrue(shutdownRequested.await(5, TimeUnit.SECONDS))
            releasePort.countDown()
            assertTrue(serviceCompleted.await(5, TimeUnit.SECONDS))
            hookThread.join(5_000)
            assertSame(null, serviceFailure.get())
            assertEquals(1, runtimeCloseCalls.get())
            assertEquals(
                listOf(
                    "service.starting",
                    "service.started",
                    "service.stopping",
                    "service.stopped",
                ),
                events.map(BackendLogEvent::eventName),
            )
            assertEquals("logging.close", order.last())
        } finally {
            releasePort.countDown()
            serviceThread.join(5_000)
        }
    }

    @Test
    fun `product command never opens backend logging`() {
        var loggingStarts = 0

        val result = rootCommand(
            runService = { loggingStarts++ },
            productDependencies = productDependencies(),
        ).test("--version")

        assertEquals(0, result.statusCode)
        assertEquals(0, loggingStarts)
    }

    @Test
    fun `service run opens logging before runtime and reuses one identity and recorder pair`(
        @TempDir directory: Path,
    ) {
        val events = mutableListOf<BackendLogEvent>()
        val order = mutableListOf<String>()
        val sessionClosed = AtomicInteger()
        val serviceInstanceIds = mutableListOf<String>()
        var openedRecorder: BackendEventRecorder? = null
        var openedOperationalRecorder: OperationalEventRecorder? = null
        val session = recordingSession(
            events = events,
            onClose = {
                order += "logging.close"
                sessionClosed.incrementAndGet()
            },
        )
        val runtime = ServiceRuntime.createForLifecycleTest(
            RuntimeLifecycleActions(
                startScheduler = { order += "scheduler.start" },
                startServers = { order += "servers.start"; 18443 },
                stopServers = { order += "servers.stop" },
                stopScheduler = { order += "scheduler.stop" },
                cancelAndJoinScope = { order += "scope.stop" },
                closeGateway = { order += "gateway.stop" },
                closePersistence = { order += "persistence.stop" },
            ),
        )
        val configuration = configuration(directory)
        val seams = ServiceBootstrapSeams(
            config = configuration.config,
            serviceInstanceIdSource = {
                order += "identity.allocate"
                "svc_test_1"
            },
            openLogging = { _, serviceInstanceId ->
                order += "logging.open.callback:$serviceInstanceId"
                serviceInstanceIds += serviceInstanceId
                openedRecorder = session.recorder
                openedOperationalRecorder = session.operationalRecorder
                session
            },
            createRuntime = { _, serviceInstanceId, backendRecorder, operationalRecorder ->
                order += "runtime.create:$serviceInstanceId"
                serviceInstanceIds += serviceInstanceId
                assertSame(openedRecorder, backendRecorder)
                assertSame(openedOperationalRecorder, operationalRecorder)
                runtime
            },
        )

        runConfiguredService(
            environment = configuration.environment,
            seams = seams,
            awaitShutdown = { shutdown -> shutdown.countDown() },
        )

        assertEquals(listOf("svc_test_1", "svc_test_1"), serviceInstanceIds)
        assertEquals(1, sessionClosed.get())
        assertEquals(
            listOf(
                "identity.allocate",
                "logging.open.callback:svc_test_1",
                "runtime.create:svc_test_1",
                "scheduler.start",
                "servers.start",
                "servers.stop",
                "scheduler.stop",
                "scope.stop",
                "gateway.stop",
                "persistence.stop",
                "logging.close",
            ),
            order,
        )
        assertEquals(
            listOf(
                "service.starting",
                "service.started",
                "service.stopping",
                "service.stopped",
            ),
            events.map(BackendLogEvent::eventName),
        )
        assertEquals(18443, (events[1] as BackendLogEvent.ServiceStarted).browserPort)
    }

    @Test
    fun `throwing backend recorder is observational for clean service lifecycle`(@TempDir directory: Path) {
        val configuration = configuration(directory)
        val recorderFailure = IllegalStateException("private recorder failure")
        var recordAttempts = 0
        val session = object : ServiceLoggingSession {
            override val recorder = BackendEventRecorder {
                recordAttempts++
                throw recorderFailure
            }
            override val operationalRecorder = OperationalEventRecorder.NONE
            override fun close() = Unit
        }
        val lifecycle = mutableListOf<String>()
        val runtime = ServiceRuntime.createForLifecycleTest(
            RuntimeLifecycleActions(
                startScheduler = { lifecycle += "start.scheduler" },
                startServers = { lifecycle += "start.servers"; 18443 },
                stopServers = { lifecycle += "stop.servers" },
                stopScheduler = { lifecycle += "stop.scheduler" },
                cancelAndJoinScope = { lifecycle += "stop.scope" },
                closeGateway = { lifecycle += "stop.gateway" },
                closePersistence = { lifecycle += "stop.persistence" },
            ),
        )
        val seams = ServiceBootstrapSeams(
            config = configuration.config,
            serviceInstanceIdSource = { "svc_throwing_recorder" },
            openLogging = { _, _ -> session },
            createRuntime = { _, _, _, _ -> runtime },
        )

        runConfiguredService(
            environment = configuration.environment,
            seams = seams,
            awaitShutdown = { shutdown -> shutdown.countDown() },
        )

        assertTrue(recordAttempts >= 4)
        assertEquals(
            listOf("start.scheduler", "start.servers", "stop.servers", "stop.scheduler", "stop.scope", "stop.gateway", "stop.persistence"),
            lifecycle,
        )
    }

    @Test
    fun `throwing backend recorder preserves primary and cleanup failures`(@TempDir directory: Path) {
        val configuration = configuration(directory)
        val recorderFailure = IllegalStateException("private recorder failure")
        val startFailure = IllegalStateException("primary start failure")
        val stopFailure = IllegalStateException("cleanup stop failure")
        val session = object : ServiceLoggingSession {
            override val recorder = BackendEventRecorder { throw recorderFailure }
            override val operationalRecorder = OperationalEventRecorder.NONE
            override fun close() = Unit
        }
        val runtime = ServiceRuntime.createForLifecycleTest(
            RuntimeLifecycleActions(
                startScheduler = { throw startFailure },
                startServers = { 18443 },
                stopServers = {},
                stopScheduler = { throw stopFailure },
                cancelAndJoinScope = {},
                closeGateway = {},
                closePersistence = {},
            ),
        )
        val seams = ServiceBootstrapSeams(
            config = configuration.config,
            serviceInstanceIdSource = { "svc_throwing_recorder_failure" },
            openLogging = { _, _ -> session },
            createRuntime = { _, _, _, _ -> runtime },
        )

        val observed = assertThrows(IllegalStateException::class.java) {
            runConfiguredService(configuration.environment, seams)
        }

        assertSame(startFailure, observed)
        assertEquals(listOf(stopFailure), observed.suppressed.toList())
        assertFalse(observed.suppressed.any { it === recorderFailure })
    }

    @Test
    fun `configuration failure after logging is recorded and rethrows the same safe exception`(
        @TempDir directory: Path,
    ) {
        val events = mutableListOf<BackendLogEvent>()
        val session = recordingSession(events)
        val configuration = configuration(directory)
        val seams = ServiceBootstrapSeams(
            config = configuration.config,
            serviceInstanceIdSource = { "svc_config_failure" },
            openLogging = { _, _ -> session },
            createRuntime = { _, _, _, _ ->
                error("runtime must not be composed after configuration failure")
            },
        )

        val failure = assertThrows(StartupConfigurationException::class.java) {
            runConfiguredService(
                environment = configuration.environment - "BITBUCKET_USERNAME",
                seams = seams,
                awaitShutdown = { shutdown -> shutdown.countDown() },
            )
        }

        val configurationFailure = events.single { it is BackendLogEvent.ServiceConfigurationFailed }
        assertSame(failure, (configurationFailure as BackendLogEvent.ServiceConfigurationFailed).failure)
        assertEquals(
            listOf("service.starting", "service.configuration.failed"),
            events.map(BackendLogEvent::eventName),
        )
    }

    @Test
    fun `hook installation failure is recorded once and preserves the original failure`(
        @TempDir directory: Path,
    ) {
        val events = mutableListOf<BackendLogEvent>()
        val order = mutableListOf<String>()
        val hookFailure = IllegalStateException("hook-install-message-sentinel")
        val session = recordingSession(events) { order += "logging.close" }
        val runtimeCloseCalls = AtomicInteger()
        val runtime = ServiceRuntime.createForLifecycleTest(
            RuntimeLifecycleActions(
                startScheduler = {},
                startServers = { 18443 },
                stopServers = {},
                stopScheduler = { runtimeCloseCalls.incrementAndGet() },
                cancelAndJoinScope = {},
                closeGateway = {},
                closePersistence = {},
            ),
            session.recorder,
        )
        val configuration = configuration(directory)
        val seams = ServiceBootstrapSeams(
            config = configuration.config,
            serviceInstanceIdSource = { "svc_hook_failure" },
            openLogging = { _, _ -> session },
            createRuntime = { _, _, _, _ -> runtime },
            installShutdownHook = { throw hookFailure },
            removeShutdownHook = {},
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            runConfiguredService(configuration.environment, seams)
        }

        assertSame(hookFailure, failure)
        assertEquals(1, runtimeCloseCalls.get())
        assertEquals(
            listOf("service.starting", "service.start.failed"),
            events.map(BackendLogEvent::eventName),
        )
        val startFailure = events.single { it is BackendLogEvent.ServiceStartFailed }
            as BackendLogEvent.ServiceStartFailed
        assertEquals("service_scope", startFailure.component)
        assertSame(hookFailure, startFailure.failure)
        assertFalse(startFailure.toString().contains("hook-install-message-sentinel"))
        assertEquals("logging.close", order.last())
    }

    @Test
    fun `runtime cleanup failure records stopped once before closing logging`(
        @TempDir directory: Path,
    ) {
        val events = mutableListOf<BackendLogEvent>()
        val order = mutableListOf<String>()
        val schedulerFailure = IllegalStateException("scheduler-cleanup-failure")
        val gatewayFailure = IllegalStateException("gateway-cleanup-failure")
        val session = recordingSession(events) { order += "logging.close" }
        val runtime = ServiceRuntime.createForLifecycleTest(
            RuntimeLifecycleActions(
                startScheduler = {},
                startServers = { 18443 },
                stopServers = {},
                stopScheduler = { order += "scheduler.close"; throw schedulerFailure },
                cancelAndJoinScope = {},
                closeGateway = { order += "gateway.close"; throw gatewayFailure },
                closePersistence = {},
            ),
            session.recorder,
        )
        val configuration = configuration(directory)
        val seams = ServiceBootstrapSeams(
            config = configuration.config,
            serviceInstanceIdSource = { "svc_cleanup_failure" },
            openLogging = { _, _ -> session },
            createRuntime = { _, _, _, _ -> runtime },
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            runConfiguredService(
                environment = configuration.environment,
                seams = seams,
                awaitShutdown = { shutdown -> shutdown.countDown() },
            )
        }

        assertSame(schedulerFailure, failure)
        assertEquals(listOf(gatewayFailure), failure.suppressed.toList())
        assertEquals(
            listOf(
                "service.starting",
                "service.started",
                "service.stopping",
                "service.stop.failed",
                "service.stop.failed",
                "service.stopped",
            ),
            events.map(BackendLogEvent::eventName),
        )
        assertEquals(1, events.count { it is BackendLogEvent.ServiceStopping })
        assertEquals(1, events.count { it is BackendLogEvent.ServiceStopped })
        assertEquals("logging.close", order.last())
    }

    @Test
    fun `production runtime composition failure records only its component event`(
        @TempDir directory: Path,
    ) {
        val events = mutableListOf<BackendLogEvent>()
        val session = recordingSession(events)
        val configuration = configuration(directory)
        val seams = ServiceBootstrapSeams(
            config = configuration.config,
            serviceInstanceIdSource = { "svc_composition_failure" },
            openLogging = { _, _ -> session },
            createRuntime = { _, serviceInstanceId, backendRecorder, operationalRecorder ->
                ServiceRuntime.create(
                    configuration = ServiceConfiguration(
                        httpHost = "127.0.0.1",
                        httpPort = 0,
                        databasePath = directory,
                        unixSocketPath = directory.resolve("service.sock"),
                        notificationExecutablePath = Path.of("/usr/bin/true"),
                        bitbucketRequestTimeout = Duration.ofMillis(100),
                        credentials = BitbucketCredentials("person@example.com", "test-token"),
                    ),
                    serviceInstanceId = serviceInstanceId,
                    backendRecorder = backendRecorder,
                    operationalRecorder = operationalRecorder,
                )
            },
        )

        assertThrows(Throwable::class.java) {
            runConfiguredService(
                environment = configuration.environment,
                seams = seams,
                awaitShutdown = { shutdown -> shutdown.countDown() },
            )
        }

        assertEquals(
            listOf("service.starting", "service.start.failed"),
            events.map(BackendLogEvent::eventName),
        )
        assertEquals("persistence", (events.last() as BackendLogEvent.ServiceStartFailed).component)
    }

    @Test
    fun `missing log parent is not created and runtime is never composed`(
        @TempDir directory: Path,
    ) {
        val missingParent = directory.resolve("missing-parent")
        val configuration = configuration(directory, missingParent.resolve("logs").toString())
        val runtimeCalls = AtomicInteger()
        val seams = ServiceBootstrapSeams(
            config = configuration.config,
            serviceInstanceIdSource = { "svc_missing_log_parent" },
            openLogging = ServiceLogging::open,
            createRuntime = { _, _, _, _ ->
                runtimeCalls.incrementAndGet()
                error("runtime must not be composed")
            },
        )

        assertThrows(StartupConfigurationException::class.java) {
            runConfiguredService(configuration.environment, seams)
        }

        assertEquals(0, runtimeCalls.get())
        assertFalse(Files.exists(missingParent))
    }

    private fun recordingSession(
        events: MutableList<BackendLogEvent>,
        onClose: () -> Unit = {},
    ): ServiceLoggingSession {
        return object : ServiceLoggingSession {
            override val recorder = BackendEventRecorder { event -> events += event }
            override val operationalRecorder = OperationalEventRecorder.NONE
            private var closed = false

            override fun close() {
                if (!closed) {
                    closed = true
                    onClose()
                }
            }
        }
    }

    private fun configuration(directory: Path, loggingDirectory: String = directory.resolve("logs").toString()): TestConfiguration {
        Files.createDirectories(directory)
        val database = directory.resolve("service.sqlite")
        val socket = directory.resolve("service.sock")
        val config = ConfigFactory.parseString(
            """
            bitbucket-helper {
              logging {
                level = DEBUG
                directory = "$loggingDirectory"
              }
              http.port = 18443
              database.path = "$database"
              unix-socket.path = "$socket"
              notification.executable = "/usr/bin/true"
              bitbucket.request-timeout = "PT1S"
            }
            """.trimIndent(),
        ).withFallback(ConfigFactory.load("application.conf")).resolve()
        return TestConfiguration(
            config = config,
            environment = mapOf(
                "BITBUCKET_USERNAME" to "person@example.com",
                "BITBUCKET_APP_PASSWORD" to "test-token",
                "BITBUCKET_HELPER_DATABASE_PATH" to database.toString(),
                "BITBUCKET_HELPER_UNIX_SOCKET_PATH" to socket.toString(),
            ),
        )
    }

    private fun productDependencies() = ProductCommandDependencies(
        socketPath = Path.of("build/root-command.sock").toAbsolutePath().normalize(),
    )

    private data class TestConfiguration(
        val config: Config,
        val environment: Map<String, String>,
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

}
