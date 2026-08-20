package com.mindtable.bitbuckethelper.bootstrap

import com.mindtable.bitbuckethelper.adapter.inbound.http.ActionItemApiV1Dependencies
import com.mindtable.bitbuckethelper.adapter.inbound.http.ConfigurationApiV1Dependencies
import com.mindtable.bitbuckethelper.adapter.inbound.http.LocalApiServerConfiguration
import com.mindtable.bitbuckethelper.adapter.inbound.http.LocalApiServerDependencies
import com.mindtable.bitbuckethelper.adapter.inbound.http.LocalApiServers
import com.mindtable.bitbuckethelper.adapter.inbound.http.ReadApiV1Dependencies
import com.mindtable.bitbuckethelper.adapter.inbound.http.RefreshRunApiV1Dependencies
import com.mindtable.bitbuckethelper.adapter.inbound.scheduler.QuartzApplicationScheduler
import com.mindtable.bitbuckethelper.adapter.inbound.scheduler.ScheduledUseCases
import com.mindtable.bitbuckethelper.adapter.inbound.scheduler.SchedulerLifecycleFailure
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.GeneratedBitbucketGateway
import com.mindtable.bitbuckethelper.adapter.outbound.notification.DesktopNotificationsProcessAdapter
import com.mindtable.bitbuckethelper.adapter.outbound.persistence.JooqApplicationPersistence
import com.mindtable.bitbuckethelper.application.model.ActivePollingAdvice
import com.mindtable.bitbuckethelper.application.model.HealthComponent
import com.mindtable.bitbuckethelper.application.model.HealthComponentSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthStatus
import com.mindtable.bitbuckethelper.application.policy.DefaultNotificationIntentPolicy
import com.mindtable.bitbuckethelper.application.policy.NotificationRetryPolicy
import com.mindtable.bitbuckethelper.application.port.inbound.AddRepository
import com.mindtable.bitbuckethelper.application.port.inbound.ConfigureWorkspace
import com.mindtable.bitbuckethelper.application.port.inbound.GetWorkspaceConfiguration
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshRepository
import com.mindtable.bitbuckethelper.application.port.inbound.RemoveRepository
import com.mindtable.bitbuckethelper.application.port.outbound.HealthComponentProbe
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEventRecorder
import com.mindtable.bitbuckethelper.application.service.ActionItemServices
import com.mindtable.bitbuckethelper.application.service.DispatchNotificationsService
import com.mindtable.bitbuckethelper.application.service.GetHealthSnapshotService
import com.mindtable.bitbuckethelper.application.service.ImmediatePostCommitNotificationDispatcher
import com.mindtable.bitbuckethelper.application.service.InMemoryRefreshRunRegistry
import com.mindtable.bitbuckethelper.application.service.PruneInactivePullRequestsService
import com.mindtable.bitbuckethelper.application.service.ReadQueryServices
import com.mindtable.bitbuckethelper.application.service.RefreshAllRepositoriesService
import com.mindtable.bitbuckethelper.application.service.RefreshRepositoryService
import com.mindtable.bitbuckethelper.application.service.RefreshRunIdSource
import com.mindtable.bitbuckethelper.application.service.RefreshRunServices
import com.mindtable.bitbuckethelper.application.service.RepositoryRefreshCoordinator
import com.mindtable.bitbuckethelper.application.service.RetryPendingNotificationsService
import com.mindtable.bitbuckethelper.application.service.SendDueRemindersService
import com.mindtable.bitbuckethelper.application.service.WorkspaceConfigurationServices
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

private fun schedulerDiagnosticFailure(failure: Throwable): Throwable =
    (failure as? SchedulerLifecycleFailure)?.diagnosticFailure ?: failure

internal data class RuntimeLifecycleActions(
    val startScheduler: () -> Unit,
    val startServers: () -> Int,
    val stopServers: () -> Unit,
    val stopScheduler: () -> Unit,
    val cancelAndJoinScope: () -> Unit,
    val closeGateway: () -> Unit,
    val closePersistence: () -> Unit,
)

class ServiceRuntime private constructor(
    private val actions: RuntimeLifecycleActions,
    private val lifecycleProbe: ServiceRuntimeLifecycleProbe,
    private val backendRecorder: BackendEventRecorder,
) : AutoCloseable {
    private val lifecycleMonitor = Any()
    private val browserPort = CompletableDeferred<Int>()
    private var lifecycleState = LifecycleState.CREATED
    private var serversStarted = false

    fun start() {
        synchronized(lifecycleMonitor) {
            check(lifecycleState != LifecycleState.CLOSED) { "Service runtime is closed" }
            check(lifecycleState == LifecycleState.CREATED) { "Service runtime is already started" }
            lifecycleState = LifecycleState.STARTING
            try {
                try {
                    lifecycleProbe.beforeResourceStart()
                } catch (failure: Throwable) {
                    recordFailure(
                        BackendLogEvent.ServiceStartFailed("service_scope", schedulerDiagnosticFailure(failure)),
                        failure,
                    )
                    throw failure
                }
                check(lifecycleState == LifecycleState.STARTING) { "Service runtime was closed while starting" }
                runStartAction("scheduler", actions.startScheduler)
                val port = runStartAction("http_servers", actions.startServers)
                serversStarted = true
                browserPort.complete(port)
                lifecycleState = LifecycleState.STARTED
            } catch (failure: Throwable) {
                closeResourcesLocked(failure)
                throw failure
            }
        }
    }

    suspend fun resolvedHttpPort(): Int = browserPort.await()

    override fun close() {
        synchronized(lifecycleMonitor) {
            closeResourcesLocked()
        }
    }

    private fun closeResourcesLocked(primaryFailure: Throwable? = null) {
        if (lifecycleState == LifecycleState.CLOSED) return
        lifecycleState = LifecycleState.CLOSED
        var failure = primaryFailure
        fun cleanup(component: String, action: () -> Unit) {
            try {
                action()
            } catch (cleanupFailure: Throwable) {
                if (failure == null) failure = cleanupFailure
                else if (cleanupFailure !== failure) failure?.addSuppressed(cleanupFailure)
                failure = recordFailure(
                    BackendLogEvent.ServiceStopFailed(component, schedulerDiagnosticFailure(cleanupFailure)),
                    failure,
                )
            }
        }
        if (serversStarted) cleanup("http_servers", actions.stopServers)
        cleanup("scheduler", actions.stopScheduler)
        cleanup("service_scope", actions.cancelAndJoinScope)
        cleanup("bitbucket_gateway", actions.closeGateway)
        cleanup("persistence", actions.closePersistence)
        if (primaryFailure == null) failure?.let { throw it }
    }

    private fun runStartAction(component: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            recordFailure(
                BackendLogEvent.ServiceStartFailed(component, schedulerDiagnosticFailure(failure)),
                failure,
            )
            throw failure
        }
    }

    private fun <T> runStartAction(component: String, action: () -> T): T {
        return try {
            action()
        } catch (failure: Throwable) {
            recordFailure(
                BackendLogEvent.ServiceStartFailed(component, schedulerDiagnosticFailure(failure)),
                failure,
            )
            throw failure
        }
    }

    private fun recordFailure(event: BackendLogEvent, primaryFailure: Throwable? = null): Throwable? {
        return try {
            backendRecorder.record(event)
            primaryFailure
        } catch (loggingFailure: Throwable) {
            if (primaryFailure == null) loggingFailure
            else {
                if (loggingFailure !== primaryFailure) primaryFailure.addSuppressed(loggingFailure)
                primaryFailure
            }
        }
    }

    companion object {
        fun create(
            configuration: ServiceConfiguration,
            clock: Clock = Clock.systemUTC(),
        ): ServiceRuntime = create(
            configuration = configuration,
            serviceInstanceId = "svc_${UUID.randomUUID()}",
            backendRecorder = BackendEventRecorder.NONE,
            operationalRecorder = OperationalEventRecorder.NONE,
            clock = clock,
            lifecycleProbe = ServiceRuntimeLifecycleProbe.NONE,
            schedulerClock = Clock.systemUTC(),
        )

        internal fun create(
            configuration: ServiceConfiguration,
            clock: Clock,
            lifecycleProbe: ServiceRuntimeLifecycleProbe,
            schedulerClock: Clock = Clock.systemUTC(),
        ): ServiceRuntime = create(
            configuration = configuration,
            serviceInstanceId = "svc_${UUID.randomUUID()}",
            backendRecorder = BackendEventRecorder.NONE,
            operationalRecorder = OperationalEventRecorder.NONE,
            clock = clock,
            lifecycleProbe = lifecycleProbe,
            schedulerClock = schedulerClock,
        )

        internal fun create(
            configuration: ServiceConfiguration,
            serviceInstanceId: String,
            backendRecorder: BackendEventRecorder,
            operationalRecorder: OperationalEventRecorder,
            clock: Clock = Clock.systemUTC(),
            lifecycleProbe: ServiceRuntimeLifecycleProbe = ServiceRuntimeLifecycleProbe.NONE,
            schedulerClock: Clock = Clock.systemUTC(),
        ): ServiceRuntime {
            var persistence: JooqApplicationPersistence? = null
            var gateway: GeneratedBitbucketGateway? = null
            var scheduler: QuartzApplicationScheduler? = null
            var servers: LocalApiServers? = null
            var constructionComponent = "service_scope"
            val serviceJob = SupervisorJob()
            val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
            try {
                constructionComponent = "persistence"
                persistence = JooqApplicationPersistence.open(
                    path = configuration.databasePath,
                    recorder = backendRecorder,
                )
                constructionComponent = "bitbucket_gateway"
                gateway = GeneratedBitbucketGateway.create(
                    requestTimeout = configuration.bitbucketRequestTimeout,
                    username = configuration.credentials.username,
                    appPassword = configuration.credentials.apiToken,
                )
                val notificationSender = DesktopNotificationsProcessAdapter(configuration.notificationExecutablePath)
                val dispatchNotifications = DispatchNotificationsService(
                    transactions = persistence,
                    sender = notificationSender,
                    retryPolicy = NotificationRetryPolicy(),
                    clock = clock,
                )
                val postCommitDispatcher = ImmediatePostCommitNotificationDispatcher(dispatchNotifications)
                val notificationPolicy = DefaultNotificationIntentPolicy()
                val refreshDelegate = RefreshRepositoryService(
                    transactions = persistence,
                    gateway = gateway,
                    intentPolicy = notificationPolicy,
                    dispatcher = postCommitDispatcher,
                    clock = clock,
                )
                val refreshCoordinator = RepositoryRefreshCoordinator(
                    transactions = persistence,
                    delegate = RefreshRepository(refreshDelegate::refresh),
                    serviceScope = serviceScope,
                    clock = clock,
                )
                val refreshAll = RefreshAllRepositoriesService(persistence, refreshCoordinator, MAX_REFRESH_CONCURRENCY)
                val retryNotifications = RetryPendingNotificationsService(persistence, dispatchNotifications, clock)
                val sendReminders = SendDueRemindersService(
                    persistence,
                    clock,
                    notificationPolicy,
                    postCommitDispatcher,
                )
                val prune = PruneInactivePullRequestsService(persistence, clock)
                constructionComponent = "scheduler"
                scheduler = QuartzApplicationScheduler.create(
                    scheduledUseCases = ScheduledUseCases(refreshAll, retryNotifications, sendReminders, prune),
                    jobTimeout = configuration.bitbucketRequestTimeout.plusSeconds(5),
                    clock = schedulerClock,
                    recorder = backendRecorder,
                )

                val read = ReadQueryServices(persistence, clock)
                val actionItems = ActionItemServices(persistence, gateway, clock)
                val workspace = WorkspaceConfigurationServices(persistence, gateway, clock)
                val registry = InMemoryRefreshRunRegistry(
                    clock = clock,
                    timeToLive = REFRESH_RUN_TTL,
                    maximumEntries = MAX_REFRESH_RUNS,
                    idSource = RefreshRunIdSource { RefreshRunId("rr_${UUID.randomUUID()}") },
                )
                val refreshRuns = RefreshRunServices(
                    transactions = persistence,
                    coordinator = refreshCoordinator,
                    registry = registry,
                    serviceScope = serviceScope,
                    pollingAdvice = ActivePollingAdvice(REFRESH_POLL_MILLIS),
                    clock = clock,
                )
                val health = GetHealthSnapshotService(
                    serviceVersion = APPLICATION_VERSION,
                    supportedApiVersion = "1",
                    serviceInstanceId = serviceInstanceId,
                    startedAt = clock.instant(),
                    probes = listOf(
                        persistenceProbe(persistence),
                        schedulerProbe(scheduler),
                        fixedProbe(HealthComponent.INSTALLATION_PATH, "paths-validated"),
                        fixedProbe(HealthComponent.NOTIFICATION_ADAPTER, "executable-validated"),
                    ),
                    operationalEventRecorder = operationalRecorder,
                )
                val apiDependencies = LocalApiServerDependencies(
                    read = ReadApiV1Dependencies(
                        read.getDashboardSnapshot,
                        read.listPullRequestsQuery,
                        read.getPullRequestQuery,
                        read.getInboxQuery,
                        read.getSynchronizationStatusQuery,
                    ),
                    actionItems = ActionItemApiV1Dependencies(
                        actionItems.getLiveActivityContent,
                        actionItems.acknowledgeActionItem,
                    ),
                    refreshRuns = RefreshRunApiV1Dependencies(
                        refreshRuns.startRefreshRun,
                        refreshRuns.getRefreshRun,
                    ),
                    configuration = ConfigurationApiV1Dependencies(
                        GetWorkspaceConfiguration(workspace::get),
                        ConfigureWorkspace(workspace::configure),
                        AddRepository(workspace::add),
                        RemoveRepository(workspace::remove),
                    ),
                    getHealthSnapshot = health,
                )
                val serverConfiguration = LocalApiServerConfiguration(
                    configuration.httpHost,
                    configuration.httpPort,
                    configuration.unixSocketPath,
                )
                val runtimeActions = RuntimeLifecycleActions(
                    startScheduler = scheduler::start,
                    startServers = {
                        LocalApiServers.start(
                            configuration = serverConfiguration,
                            dependencies = apiDependencies,
                            serviceInstanceId = serviceInstanceId,
                            backendEventRecorder = backendRecorder,
                        )
                            .also { servers = it }
                            .browserPort
                    },
                    stopServers = { servers?.close() },
                    stopScheduler = scheduler::close,
                    cancelAndJoinScope = { runBlocking { serviceJob.cancelAndJoin() } },
                    closeGateway = gateway::close,
                    closePersistence = persistence::close,
                )
                return ServiceRuntime(runtimeActions, lifecycleProbe, backendRecorder)
            } catch (failure: Throwable) {
                recordFailure(
                    backendRecorder,
                    BackendLogEvent.ServiceStartFailed(
                        constructionComponent,
                        schedulerDiagnosticFailure(failure),
                    ),
                    failure,
                )
                closeConstructed(
                    component = "http_servers",
                    action = { servers?.close() },
                    primaryFailure = failure,
                    backendRecorder = backendRecorder,
                )
                closeConstructed(
                    component = "scheduler",
                    action = { scheduler?.close() },
                    primaryFailure = failure,
                    backendRecorder = backendRecorder,
                )
                closeConstructed(
                    component = "service_scope",
                    action = { runBlocking { serviceJob.cancelAndJoin() } },
                    primaryFailure = failure,
                    backendRecorder = backendRecorder,
                )
                closeConstructed(
                    component = "bitbucket_gateway",
                    action = { gateway?.close() },
                    primaryFailure = failure,
                    backendRecorder = backendRecorder,
                )
                closeConstructed(
                    component = "persistence",
                    action = { persistence?.close() },
                    primaryFailure = failure,
                    backendRecorder = backendRecorder,
                )
                throw failure
            }
        }

        internal fun createForLifecycleTest(
            actions: RuntimeLifecycleActions,
            backendRecorder: BackendEventRecorder = BackendEventRecorder.NONE,
        ): ServiceRuntime = ServiceRuntime(actions, ServiceRuntimeLifecycleProbe.NONE, backendRecorder)

        private fun closeConstructed(
            component: String,
            action: () -> Unit,
            primaryFailure: Throwable,
            backendRecorder: BackendEventRecorder,
        ) {
            val cleanupFailure = runCatching { action() }.exceptionOrNull() ?: return
            if (cleanupFailure !== primaryFailure) primaryFailure.addSuppressed(cleanupFailure)
            recordFailure(
                backendRecorder,
                BackendLogEvent.ServiceStopFailed(component, schedulerDiagnosticFailure(cleanupFailure)),
                primaryFailure,
            )
        }

        private fun recordFailure(
            backendRecorder: BackendEventRecorder,
            event: BackendLogEvent,
            primaryFailure: Throwable? = null,
        ) {
            try {
                backendRecorder.record(event)
            } catch (loggingFailure: Throwable) {
                if (primaryFailure != null && loggingFailure !== primaryFailure) {
                    primaryFailure.addSuppressed(loggingFailure)
                }
            }
        }

        private fun persistenceProbe(persistence: JooqApplicationPersistence): HealthComponentProbe =
            object : HealthComponentProbe {
                override val component = HealthComponent.PERSISTENCE
                override suspend fun probe(): HealthComponentSnapshot {
                    persistence.inTransaction { configurationStore.find() }
                    return HealthComponentSnapshot(component, HealthStatus.HEALTHY, "persistence-ready")
                }
            }

        private fun schedulerProbe(scheduler: QuartzApplicationScheduler): HealthComponentProbe =
            object : HealthComponentProbe {
                override val component = HealthComponent.SCHEDULER
                override suspend fun probe(): HealthComponentSnapshot = scheduler.health()
            }

        private fun fixedProbe(component: HealthComponent, safeCode: String): HealthComponentProbe =
            object : HealthComponentProbe {
                override val component = component
                override suspend fun probe() = HealthComponentSnapshot(component, HealthStatus.HEALTHY, safeCode)
            }

        private const val APPLICATION_VERSION = "0.1.0"
        private const val MAX_REFRESH_CONCURRENCY = 4
        private const val MAX_REFRESH_RUNS = 1_000
        private const val REFRESH_POLL_MILLIS = 100L
        private val REFRESH_RUN_TTL: Duration = Duration.ofMinutes(15)
    }

    private enum class LifecycleState { CREATED, STARTING, STARTED, CLOSED }
}

internal fun interface ServiceRuntimeLifecycleProbe {
    fun beforeResourceStart()

    companion object {
        val NONE = ServiceRuntimeLifecycleProbe { }
    }
}
