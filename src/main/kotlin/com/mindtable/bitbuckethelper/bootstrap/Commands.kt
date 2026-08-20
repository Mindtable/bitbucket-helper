package com.mindtable.bitbuckethelper.bootstrap

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEventRecorder
import com.mindtable.bitbuckethelper.cli.ProductCommandDependencies
import com.mindtable.bitbuckethelper.cli.productCommands
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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
)

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
    val stopped = AtomicBoolean()
    val loggingClosed = AtomicBoolean()
    val shutdownFailure = AtomicReference<Throwable?>()
    var logging: ServiceLoggingSession? = null
    var runtime: ServiceRuntime? = null
    var serviceStarted = false
    var serviceStartedAt = 0L
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
            if (failure == null) loggingFailure
            else mergeFailure(failure, loggingFailure)
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
        if (!stopped.compareAndSet(false, true)) return shutdownFailure.get()

        var failure: Throwable? = null
        if (serviceStarted) {
            failure = record(
                BackendLogEvent.ServiceStopping(reasonCategory),
                failure,
            )
            val activeRuntime = runtime
            if (activeRuntime != null) {
                try {
                    activeRuntime.close()
                } catch (runtimeFailure: Throwable) {
                    failure = mergeFailure(failure, runtimeFailure)
                }
            }
            val durationMilliseconds = ((System.nanoTime() - serviceStartedAt) / 1_000_000L).coerceAtLeast(0L)
            failure = record(
                BackendLogEvent.ServiceStopped(durationMilliseconds),
                failure,
            )
        } else {
            val activeRuntime = runtime
            if (activeRuntime != null) {
                try {
                    activeRuntime.close()
                } catch (runtimeFailure: Throwable) {
                    failure = mergeFailure(failure, runtimeFailure)
                }
            }
        }
        failure = closeLogging(failure)
        shutdownFailure.set(failure)
        return failure
    }

    val shutdownHook = Thread(
        {
            try {
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
        ensureDefaultLoggingParent(loggingConfiguration.directory)
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

        val activeRuntime = try {
            seams.createRuntime(
                serviceConfiguration,
                serviceInstanceId,
                checkNotNull(logging).recorder,
                checkNotNull(logging).operationalRecorder,
            )
        } catch (failure: Throwable) {
            primaryFailure = record(
                BackendLogEvent.ServiceStartFailed("service_scope", failure),
                failure,
            )
            throw failure
        }
        runtime = activeRuntime
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        hookInstalled = true
        activeRuntime.start()
        serviceStarted = true
        serviceStartedAt = System.nanoTime()
        val browserPort = try {
            runBlocking { activeRuntime.resolvedHttpPort() }
        } catch (failure: Throwable) {
            primaryFailure = record(
                BackendLogEvent.ServiceStartFailed("http_servers", failure),
                failure,
            )
            throw failure
        }
        primaryFailure = record(
            BackendLogEvent.ServiceStarted(browserPort),
        )
        primaryFailure?.let { throw it }
        awaitShutdown(shutdown)
        shutdownFailure.get()?.let { throw it }
    } catch (failure: Throwable) {
        if (logging != null) ServiceBootstrapFailureState.activeFailureHandled = true
        primaryFailure = mergeFailure(primaryFailure, failure)
        throw failure
    } finally {
        if (hookInstalled) {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
        val stopFailure = stopService(if (serviceStarted) "shutdown" else "startup_failure")
        if (stopFailure != null) {
            if (primaryFailure == null) throw stopFailure
            if (stopFailure !== primaryFailure) primaryFailure.addSuppressed(stopFailure)
        }
    }
}

private fun serviceConfigurationSettingCode(failure: Throwable): String {
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
    "bitbucket-helper.bitbucket.request-timeout",
)

private fun ensureDefaultLoggingParent(directory: Path) {
    val defaultDirectory = Path.of("./var/log").toAbsolutePath().normalize()
    if (directory != defaultDirectory) return
    val parent = directory.parent ?: return
    if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) return
    val grandparent = parent.parent
        ?: throw StartupConfigurationException("BITBUCKET_HELPER_LOG_DIRECTORY parent must be a real directory")
    try {
        val attributes = Files.readAttributes(
            grandparent,
            java.nio.file.attribute.PosixFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        val currentUser = System.getProperty("user.name")
        val owner = attributes.owner().name
        if (!attributes.isDirectory || attributes.isSymbolicLink ||
            (owner != currentUser && owner.substringAfterLast('\\') != currentUser) ||
            !Files.isWritable(grandparent)
        ) {
            throw StartupConfigurationException(
                "BITBUCKET_HELPER_LOG_DIRECTORY parent must be a current-user-owned writable directory",
            )
        }
        Files.createDirectory(
            parent,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
    } catch (failure: StartupConfigurationException) {
        throw failure
    } catch (_: Exception) {
        throw StartupConfigurationException("BITBUCKET_HELPER_LOG_DIRECTORY parent could not be created safely")
    }
}

internal fun <T> loadAndCreateRuntime(
    config: Config,
    environment: Map<String, String>,
    create: (ServiceConfiguration) -> T,
): T = create(ServiceConfigurationLoader.load(config, environment))
