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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ServiceLoggingBootstrapTest {
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

    private fun configuration(directory: Path): TestConfiguration {
        Files.createDirectories(directory)
        val database = directory.resolve("service.sqlite")
        val socket = directory.resolve("service.sock")
        val config = ConfigFactory.parseString(
            """
            bitbucket-helper {
              logging {
                level = DEBUG
                directory = "${directory.resolve("logs")}"
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
}
