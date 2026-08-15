package com.mindtable.bitbuckethelper.bootstrap

import com.mindtable.bitbuckethelper.adapter.inbound.http.installBitbucketStatusApi
import com.mindtable.bitbuckethelper.adapter.inbound.scheduler.QuartzRefreshScheduler
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.GeneratedBitbucketAccountGateway
import com.mindtable.bitbuckethelper.adapter.outbound.persistence.JooqBitbucketConnectionRepository
import com.mindtable.bitbuckethelper.adapter.outbound.persistence.SqliteDatabase
import com.mindtable.bitbuckethelper.application.service.GetBitbucketConnectionStatusService
import com.mindtable.bitbuckethelper.application.service.RefreshBitbucketConnectionService
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.time.Clock
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

class ServiceRuntime private constructor(
    private val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
    private val scheduler: QuartzRefreshScheduler,
    private val bitbucketGateway: GeneratedBitbucketAccountGateway,
    private val databaseDispatcher: ExecutorCoroutineDispatcher,
    private val database: SqliteDatabase,
    private val lifecycleProbe: ServiceRuntimeLifecycleProbe,
) : AutoCloseable {
    private val lifecycleMonitor = Any()
    private var lifecycleState = LifecycleState.CREATED
    private var serverStartAttempted = false

    fun start() {
        synchronized(lifecycleMonitor) {
            check(lifecycleState != LifecycleState.CLOSED) { "Service runtime is closed" }
            check(lifecycleState == LifecycleState.CREATED) {
                "Service runtime is already started"
            }
            lifecycleState = LifecycleState.STARTING
            try {
                lifecycleProbe.beforeResourceStart()
                check(lifecycleState == LifecycleState.STARTING) {
                    "Service runtime was closed while starting"
                }
                scheduler.start()
                serverStartAttempted = true
                server.start(wait = false)
                lifecycleState = LifecycleState.STARTED
            } catch (failure: Throwable) {
                closeResourcesLocked(failure)
                throw failure
            }
        }
    }

    suspend fun resolvedHttpPort(): Int = server.engine.resolvedConnectors().single().port

    override fun close() {
        synchronized(lifecycleMonitor) {
            closeResourcesLocked()
        }
    }

    private fun closeResourcesLocked(primaryFailure: Throwable? = null) {
        if (lifecycleState == LifecycleState.CLOSED) return
        lifecycleState = LifecycleState.CLOSED
        if (serverStartAttempted) {
            attemptCleanup(primaryFailure) {
                server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
            }
        }
        attemptCleanup(primaryFailure) { scheduler.close() }
        attemptCleanup(primaryFailure) { bitbucketGateway.close() }
        attemptCleanup(primaryFailure) { databaseDispatcher.close() }
        attemptCleanup(primaryFailure) { database.close() }
    }

    companion object {
        fun create(
            configuration: ServiceConfiguration,
            clock: Clock = Clock.systemUTC(),
        ): ServiceRuntime = create(
            configuration = configuration,
            clock = clock,
            lifecycleProbe = ServiceRuntimeLifecycleProbe.NONE,
        )

        internal fun create(
            configuration: ServiceConfiguration,
            clock: Clock,
            lifecycleProbe: ServiceRuntimeLifecycleProbe,
        ): ServiceRuntime {
            var database: SqliteDatabase? = null
            var databaseDispatcher: ExecutorCoroutineDispatcher? = null
            var bitbucketGateway: GeneratedBitbucketAccountGateway? = null
            var scheduler: QuartzRefreshScheduler? = null

            try {
                database = SqliteDatabase.open(configuration.databasePath)
                database.migrate()
                databaseDispatcher = Executors.newFixedThreadPool(DATABASE_THREAD_COUNT)
                    .asCoroutineDispatcher()
                val repository = JooqBitbucketConnectionRepository(
                    dataSource = database.dataSource,
                    dispatcher = databaseDispatcher,
                )
                bitbucketGateway = GeneratedBitbucketAccountGateway.create(
                    baseUrl = configuration.bitbucketBaseUrl,
                    requestTimeout = configuration.bitbucketRequestTimeout,
                    username = configuration.credentials.username,
                    apiToken = configuration.credentials.apiToken,
                )
                val refresh = RefreshBitbucketConnectionService(
                    gateway = bitbucketGateway,
                    repository = repository,
                    clock = clock,
                )
                val query = GetBitbucketConnectionStatusService(repository)
                scheduler = QuartzRefreshScheduler.create(
                    refresh = refresh,
                    refreshInterval = configuration.refreshInterval,
                    jobTimeout = configuration.bitbucketRequestTimeout.plusSeconds(5),
                )
                val server = embeddedServer(
                    factory = CIO,
                    host = configuration.httpHost,
                    port = configuration.httpPort,
                ) {
                    installBitbucketStatusApi(query)
                }
                return ServiceRuntime(
                    server = server,
                    scheduler = scheduler,
                    bitbucketGateway = bitbucketGateway,
                    databaseDispatcher = databaseDispatcher,
                    database = database,
                    lifecycleProbe = lifecycleProbe,
                )
            } catch (failure: Throwable) {
                attemptCleanup(failure) { scheduler?.close() }
                attemptCleanup(failure) { bitbucketGateway?.close() }
                attemptCleanup(failure) { databaseDispatcher?.close() }
                attemptCleanup(failure) { database?.close() }
                throw failure
            }
        }

        private const val DATABASE_THREAD_COUNT = 2
    }

    private enum class LifecycleState {
        CREATED,
        STARTING,
        STARTED,
        CLOSED,
    }
}

internal fun interface ServiceRuntimeLifecycleProbe {
    fun beforeResourceStart()

    companion object {
        val NONE = ServiceRuntimeLifecycleProbe { }
    }
}

private inline fun attemptCleanup(primaryFailure: Throwable?, cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (cleanupFailure: Throwable) {
        if (primaryFailure != null && cleanupFailure !== primaryFailure) {
            primaryFailure.addSuppressed(cleanupFailure)
        }
    }
}
