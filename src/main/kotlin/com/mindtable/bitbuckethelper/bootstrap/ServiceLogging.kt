package com.mindtable.bitbuckethelper.bootstrap

import com.mindtable.bitbuckethelper.adapter.outbound.observability.Log4jOperationalEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.Log4jBackendEventRecorder
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEventRecorder
import java.io.PrintStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.Configurator

interface ServiceLoggingSession : AutoCloseable {
    val recorder: BackendEventRecorder
    val operationalRecorder: OperationalEventRecorder

    override fun close()
}

object ServiceLogging {
    private const val LEVEL_PROPERTY = "bitbucketHelper.logging.level"
    private const val DIRECTORY_PROPERTY = "bitbucketHelper.logging.directory"
    private const val SERVICE_INSTANCE_PROPERTY = "bitbucketHelper.service.instance.id"
    private const val ROOT_LEVEL_PROPERTY = "bitbucketHelper.logging.root.level"
    private const val ACTIVE_FILE_NAME = "bitbucket-helper.jsonl"
    private val OWNER_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")

    fun open(
        configuration: LoggingConfiguration,
        serviceInstanceId: String,
    ): ServiceLoggingSession = openInternal(configuration, serviceInstanceId, System.err, false)

    /** The terminal parameter is a test/embedding seam; production uses stderr. */
    fun open(
        configuration: LoggingConfiguration,
        serviceInstanceId: String,
        terminal: PrintStream,
    ): ServiceLoggingSession = openInternal(configuration, serviceInstanceId, terminal, true)

    fun open(
        configuration: LoggingConfiguration,
        serviceInstanceId: String,
        terminal: OutputStream,
    ): ServiceLoggingSession = open(configuration, serviceInstanceId, PrintStream(terminal, true, Charsets.UTF_8))

    private fun openInternal(
        configuration: LoggingConfiguration,
        serviceInstanceId: String,
        terminal: PrintStream,
        directTerminal: Boolean,
    ): ServiceLoggingSession {
        val directory = SecureLoggingDirectory.prepare(configuration.directory)
        val activeFile = directory.resolve(ACTIVE_FILE_NAME)
        prepareActiveFile(activeFile)

        System.setProperty(LEVEL_PROPERTY, configuration.level.name)
        System.setProperty(DIRECTORY_PROPERTY, directory.toString())
        System.setProperty(SERVICE_INSTANCE_PROPERTY, serviceInstanceId)
        System.setProperty(ROOT_LEVEL_PROPERTY, "INFO")

        val previousError = System.err
        val context = try {
            // Console's SYSTEM_ERR target is bound while the service session is
            // active. The session restores stderr after the final flush.
            System.setErr(terminal)
            org.apache.logging.log4j.LogManager.shutdown()
            Configurator.initialize(
                "bitbucket-helper",
                ServiceLogging::class.java.classLoader,
                "classpath:log4j2.xml",
            )
        } catch (failure: RuntimeException) {
            System.setErr(previousError)
            clearLoggingProperties()
            throw StartupConfigurationException("BITBUCKET_HELPER_LOG_DIRECTORY logging initialization failed")
        }

        try {
            verifyActiveFile(activeFile)
        } catch (failure: RuntimeException) {
            context.stop()
            clearLoggingProperties()
            System.setErr(previousError)
            throw failure
        }
        val recorder = Log4jBackendEventRecorder(
            serviceInstanceId = serviceInstanceId,
            terminal = terminal.takeIf { directTerminal },
            minimumLevel = configuration.level.toBackendLevel(),
        )
        val operationalRecorder = Log4jOperationalEventRecorder(recorder)
        return Session(context, recorder, operationalRecorder, previousError)
    }

    private fun ServiceLogLevel.toBackendLevel() = when (this) {
        ServiceLogLevel.TRACE -> com.mindtable.bitbuckethelper.observability.BackendLogLevel.TRACE
        ServiceLogLevel.DEBUG -> com.mindtable.bitbuckethelper.observability.BackendLogLevel.DEBUG
        ServiceLogLevel.INFO -> com.mindtable.bitbuckethelper.observability.BackendLogLevel.INFO
        ServiceLogLevel.WARN -> com.mindtable.bitbuckethelper.observability.BackendLogLevel.WARN
        ServiceLogLevel.ERROR -> com.mindtable.bitbuckethelper.observability.BackendLogLevel.ERROR
    }

    private fun clearLoggingProperties() {
        System.clearProperty(LEVEL_PROPERTY)
        System.clearProperty(DIRECTORY_PROPERTY)
        System.clearProperty(SERVICE_INSTANCE_PROPERTY)
        System.clearProperty(ROOT_LEVEL_PROPERTY)
    }

    private fun prepareActiveFile(path: Path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_FILE_PERMISSIONS))
            }
        } catch (_: Exception) {
            throw StartupConfigurationException("BITBUCKET_HELPER_LOG_DIRECTORY active log file is unavailable")
        }
        verifyActiveFile(path)
    }

    private fun verifyActiveFile(path: Path) {
        val attributes = try {
            Files.readAttributes(
                path,
                java.nio.file.attribute.PosixFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: Exception) {
            throw StartupConfigurationException("BITBUCKET_HELPER_LOG_DIRECTORY active log file is unavailable")
        }
        val owner = attributes.owner().name
        val currentUser = System.getProperty("user.name")
        if (!attributes.isRegularFile || attributes.isSymbolicLink ||
            (owner != currentUser && owner.substringAfterLast('\\') != currentUser) ||
            attributes.permissions() != OWNER_FILE_PERMISSIONS || !Files.isWritable(path)
        ) {
            throw StartupConfigurationException(
                "BITBUCKET_HELPER_LOG_DIRECTORY active log file must be a current-user-owned owner-only regular file",
            )
        }
    }

    private class Session(
        private val context: LoggerContext,
        override val recorder: BackendEventRecorder,
        override val operationalRecorder: OperationalEventRecorder,
        private val previousError: PrintStream,
    ) : ServiceLoggingSession {
        @Volatile
        private var closed = false

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            // LoggerContext.stop() stops each synchronous appender after it
            // has flushed its manager, then releases the context idempotently.
            context.stop()
            clearLoggingProperties()
            System.setErr(previousError)
        }
    }
}
