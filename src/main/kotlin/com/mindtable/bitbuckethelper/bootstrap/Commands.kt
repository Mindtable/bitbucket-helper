package com.mindtable.bitbuckethelper.bootstrap

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEventRecorder
import com.mindtable.bitbuckethelper.cli.ProductCommandDependencies
import com.mindtable.bitbuckethelper.cli.productCommands
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.reportBackendEventRecorderFailure
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.runBlocking

private const val APPLICATION_VERSION = "0.1.0"

private class BitbucketHelperCommand : CliktCommand(name = "bitbucket-helper") {
    init { versionOption(APPLICATION_VERSION) }
    override fun run() = Unit
}

private class ServiceCommand : CliktCommand(name = "service") {
    override fun run() = Unit
}

private class ServiceRunCommand(
    private val runService: () -> Unit,
) : CliktCommand(name = "run") {
    override fun run() = runService()
}

fun rootCommand(
    runService: () -> Unit,
    productDependencies: ProductCommandDependencies,
): CliktCommand =
    BitbucketHelperCommand().subcommands(
        ServiceCommand().subcommands(ServiceRunCommand(runService)),
        *productCommands(productDependencies).toTypedArray(),
    )

internal data class ServiceBootstrapSeams(
    val config: Config,
    val serviceInstanceIdSource: () -> String,
    val openLogging: (LoggingConfiguration, String) -> ServiceLoggingSession,
    val createRuntime: (
        ServiceConfiguration,
        String,
        BackendEventRecorder,
        OperationalEventRecorder,
    ) -> ServiceRuntime,
    val installShutdownHook: (Thread) -> Unit = { Runtime.getRuntime().addShutdownHook(it) },
    val removeShutdownHook: (Thread) -> Unit = { Runtime.getRuntime().removeShutdownHook(it) },
    val resolveBrowserPort: suspend (ServiceRuntime) -> Int = { it.resolvedHttpPort() },
    val onShutdownRequested: () -> Unit = {},
)

private enum class ServiceBootstrapState {
    IDLE,
    STARTING,
    STARTED,
    FAILED,
    STOPPING,
    STOPPED,
}

internal object ServiceBootstrapFailureState {
    @Volatile
    var activeFailureHandled: Boolean = false
}

fun runConfiguredService(environment: Map<String, String> = System.getenv()) {
    val config = ConfigFactory.load("application.conf")
    runConfiguredService(
        environment = environment,
        seams = ServiceBootstrapSeams(
            config = config,
            serviceInstanceIdSource = { "svc_${UUID.randomUUID()}" },
            openLogging = ServiceLogging::open,
            createRuntime = { configuration, serviceInstanceId, backendRecorder, operationalRecorder ->
                ServiceRuntime.create(
                    configuration = configuration,
                    serviceInstanceId = serviceInstanceId,
                    backendRecorder = backendRecorder,
                    operationalRecorder = operationalRecorder,
                    clock = java.time.Clock.systemUTC(),
                    lifecycleProbe = ServiceRuntimeLifecycleProbe.NONE,
                    schedulerClock = java.time.Clock.systemUTC(),
                )
            },
        ),
    )
}

internal fun runConfiguredService(
    environment: Map<String, String>,
    seams: ServiceBootstrapSeams,
    awaitShutdown: (CountDownLatch) -> Unit = { it.await() },
) {
    ServiceBootstrapFailureState.activeFailureHandled = false
    val shutdown = CountDownLatch(1)
    val loggingClosed = AtomicBoolean()
    val shutdownFailure = AtomicReference<Throwable?>()
    val lifecycleLock = ReentrantLock()
    val startupCompleted = lifecycleLock.newCondition()
    var logging: ServiceLoggingSession? = null
    var runtime: ServiceRuntime? = null
    var serviceStartedAt = 0L
    var lifecycleState = ServiceBootstrapState.IDLE
    var primaryFailure: Throwable? = null

    fun mergeFailure(current: Throwable?, next: Throwable): Throwable {
        if (current == null) return next
        if (next !== current) current.addSuppressed(next)
        return current
    }

    fun record(event: BackendLogEvent, failure: Throwable? = null): Throwable? {
        val recorder = logging?.recorder ?: return failure
        return try {
            recorder.record(event)
            failure
        } catch (loggingFailure: Throwable) {
            reportBackendEventRecorderFailure()
            failure
        }
    }

    fun closeLogging(failure: Throwable?): Throwable? {
        val session = logging ?: return failure
        if (!loggingClosed.compareAndSet(false, true)) return failure
        return try {
            session.close()
            failure
        } catch (loggingFailure: Throwable) {
            if (failure == null) loggingFailure
            else mergeFailure(failure, loggingFailure)
        }
    }

    fun stopService(reasonCategory: String): Throwable? {
        lifecycleLock.lock()
        try {
            while (lifecycleState == ServiceBootstrapState.STARTING) {
                startupCompleted.awaitUninterruptibly()
            }
            if (lifecycleState == ServiceBootstrapState.STOPPING ||
                lifecycleState == ServiceBootstrapState.STOPPED
            ) {
                return shutdownFailure.get()
            }

            val wasStarted = lifecycleState == ServiceBootstrapState.STARTED
            lifecycleState = ServiceBootstrapState.STOPPING
            var failure: Throwable? = null
            if (wasStarted) {
                failure = record(BackendLogEvent.ServiceStopping(reasonCategory), failure)
            }
            val activeRuntime = runtime
            if (activeRuntime != null) {
                try {
                    activeRuntime.close()
                } catch (runtimeFailure: Throwable) {
                    failure = mergeFailure(failure, runtimeFailure)
                }
            }
            if (wasStarted) {
                val durationMilliseconds =
                    ((System.nanoTime() - serviceStartedAt) / 1_000_000L).coerceAtLeast(0L)
                failure = record(BackendLogEvent.ServiceStopped(durationMilliseconds), failure)
            }
            failure = closeLogging(failure)
            lifecycleState = ServiceBootstrapState.STOPPED
            shutdownFailure.set(failure)
            startupCompleted.signalAll()
            return failure
        } finally {
            lifecycleLock.unlock()
        }
    }

    val shutdownHook = Thread(
        {
            try {
                runCatching { seams.onShutdownRequested() }
                stopService("shutdown")?.let(shutdownFailure::set)
            } finally {
                shutdown.countDown()
            }
        },
        "bitbucket-helper-shutdown",
    )
    var hookInstalled = false

    try {
        val loggingConfiguration = LoggingConfigurationLoader.load(seams.config, environment)
        val serviceInstanceId = seams.serviceInstanceIdSource()
        logging = seams.openLogging(loggingConfiguration, serviceInstanceId)
        primaryFailure = record(
            BackendLogEvent.ServiceStarting(
                version = APPLICATION_VERSION,
                configuredLevel = loggingConfiguration.level.name,
            ),
            primaryFailure,
        )
        primaryFailure?.let { throw it }

        val serviceConfiguration = try {
            ServiceConfigurationLoader.load(seams.config, environment)
        } catch (failure: Throwable) {
            primaryFailure = record(
                BackendLogEvent.ServiceConfigurationFailed(
                    settingCode = serviceConfigurationSettingCode(failure),
                    failure = failure,
                ),
                failure,
            )
            throw failure
        }

        val activeRuntime = seams.createRuntime(
            serviceConfiguration,
            serviceInstanceId,
            checkNotNull(logging).recorder,
            checkNotNull(logging).operationalRecorder,
        )
        runtime = activeRuntime

        lifecycleLock.lock()
        try {
            lifecycleState = ServiceBootstrapState.STARTING
        } finally {
            lifecycleLock.unlock()
        }

        try {
            seams.installShutdownHook(shutdownHook)
            hookInstalled = true
        } catch (failure: Throwable) {
            lifecycleLock.lock()
            try {
                record(BackendLogEvent.ServiceStartFailed("service_scope", failure), failure)
                lifecycleState = ServiceBootstrapState.FAILED
                startupCompleted.signalAll()
            } finally {
                lifecycleLock.unlock()
            }
            throw failure
        }

        lifecycleLock.lock()
        try {
            try {
                activeRuntime.start()
                serviceStartedAt = System.nanoTime()
                val browserPort = try {
                    runBlocking { seams.resolveBrowserPort(activeRuntime) }
                } catch (failure: Throwable) {
                    val loggedFailure = record(
                        BackendLogEvent.ServiceStartFailed("http_servers", failure),
                        failure,
                    )
                    throw loggedFailure ?: failure
                }
                val startedFailure = record(BackendLogEvent.ServiceStarted(browserPort))
                startedFailure?.let { throw it }
                lifecycleState = ServiceBootstrapState.STARTED
                startupCompleted.signalAll()
            } catch (failure: Throwable) {
                lifecycleState = ServiceBootstrapState.FAILED
                startupCompleted.signalAll()
                throw failure
            }
        } finally {
            lifecycleLock.unlock()
        }
        awaitShutdown(shutdown)
        shutdownFailure.get()?.let { throw it }
    } catch (failure: Throwable) {
        if (logging != null) ServiceBootstrapFailureState.activeFailureHandled = true
        primaryFailure = mergeFailure(primaryFailure, failure)
        throw failure
    } finally {
        if (hookInstalled) {
            runCatching { seams.removeShutdownHook(shutdownHook) }
        }
        val stopFailure = stopService("shutdown")
        if (stopFailure != null) {
            if (primaryFailure == null) throw stopFailure
            if (stopFailure !== primaryFailure) primaryFailure.addSuppressed(stopFailure)
        }
    }
}

internal fun serviceConfigurationSettingCode(failure: Throwable): String {
    val message = failure.message.orEmpty()
    return KNOWN_CONFIGURATION_SETTING_CODES.firstOrNull(message::contains)
        ?: "service_configuration"
}

private val KNOWN_CONFIGURATION_SETTING_CODES = listOf(
    "BITBUCKET_USERNAME",
    "BITBUCKET_APP_PASSWORD",
    "BITBUCKET_HELPER_HTTP_PORT",
    "BITBUCKET_HELPER_DATABASE_PATH",
    "BITBUCKET_HELPER_UNIX_SOCKET_PATH",
    "BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE",
    "BITBUCKET_HELPER_LOG_LEVEL",
    "BITBUCKET_HELPER_LOG_DIRECTORY",
    "bitbucket-helper.bitbucket.request-timeout",
)

internal fun <T> loadAndCreateRuntime(
    config: Config,
    environment: Map<String, String>,
    create: (ServiceConfiguration) -> T,
): T = create(ServiceConfigurationLoader.load(config, environment))
