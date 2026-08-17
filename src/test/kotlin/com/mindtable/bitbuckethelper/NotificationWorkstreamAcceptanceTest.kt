package com.mindtable.bitbuckethelper

import com.mindtable.bitbuckethelper.adapter.inbound.scheduler.QuartzApplicationScheduler
import com.mindtable.bitbuckethelper.adapter.inbound.scheduler.ScheduledUseCases
import com.mindtable.bitbuckethelper.adapter.outbound.notification.BoundedProcessCapture
import com.mindtable.bitbuckethelper.adapter.outbound.notification.DesktopNotificationsProcessAdapter
import com.mindtable.bitbuckethelper.adapter.outbound.notification.NotificationProcessResult
import com.mindtable.bitbuckethelper.application.model.NotificationAttemptCompletion
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryFailureCategory
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryResult
import com.mindtable.bitbuckethelper.application.model.NotificationDispatchSummary
import com.mindtable.bitbuckethelper.application.model.NotificationIntentInsertResult
import com.mindtable.bitbuckethelper.application.model.NotificationIntentState
import com.mindtable.bitbuckethelper.application.model.NotificationLease
import com.mindtable.bitbuckethelper.application.model.NotificationRequest
import com.mindtable.bitbuckethelper.application.model.NotificationSound
import com.mindtable.bitbuckethelper.application.model.NotificationTransitionFact
import com.mindtable.bitbuckethelper.application.model.PruneInactivePullRequestsResult
import com.mindtable.bitbuckethelper.application.model.RefreshAllRepositoriesResult
import com.mindtable.bitbuckethelper.application.model.ReminderActionItemProjection
import com.mindtable.bitbuckethelper.application.model.ReminderRepositoryProjection
import com.mindtable.bitbuckethelper.application.model.StoredNotificationAttempt
import com.mindtable.bitbuckethelper.application.model.StoredNotificationIntent
import com.mindtable.bitbuckethelper.application.policy.DefaultNotificationIntentPolicy
import com.mindtable.bitbuckethelper.application.policy.NotificationRetryPolicy
import com.mindtable.bitbuckethelper.application.port.inbound.PruneInactivePullRequests
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshAllRepositories
import com.mindtable.bitbuckethelper.application.port.inbound.RetryPendingNotifications
import com.mindtable.bitbuckethelper.application.port.inbound.SendDueReminders
import com.mindtable.bitbuckethelper.application.port.outbound.ActionItemStore
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransaction
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.application.port.outbound.ConfigurationStore
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationIntentStore
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationSender
import com.mindtable.bitbuckethelper.application.port.outbound.PullRequestStore
import com.mindtable.bitbuckethelper.application.port.outbound.ReminderProjectionStore
import com.mindtable.bitbuckethelper.application.port.outbound.SynchronizationCheckpointStore
import com.mindtable.bitbuckethelper.application.service.DispatchNotificationsService
import com.mindtable.bitbuckethelper.application.service.ImmediatePostCommitNotificationDispatcher
import com.mindtable.bitbuckethelper.application.service.RetryPendingNotificationsService
import com.mindtable.bitbuckethelper.application.service.SendDueRemindersService
import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.support.FakeDesktopNotificationsExecutable
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NotificationWorkstreamAcceptanceTest {
    @Test
    fun `committed notification fails safely then retries unchanged and reminders are hourly idempotent`(
        @TempDir directory: Path,
    ) = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-08-15T12:34:00Z"))
        val repositoryId = RepositoryId("repo_acceptance")
        val policy = DefaultNotificationIntentPolicy()
        val argumentsFile = directory.resolve("arguments.txt")
        val providerState = directory.resolve("provider-state")
        val executable = FakeDesktopNotificationsExecutable.create(
            directory,
            """
                printf '%s\n' "${'$'}@" >> '${argumentsFile.toAbsolutePath()}'
                if [ ! -f '${providerState.toAbsolutePath()}' ]; then
                  : > '${providerState.toAbsolutePath()}'
                  printf '%s\n' '{"status":"failed","error":{"code":"dependency_unavailable","message":"$RAW_MARKER"}}'
                  exit 1
                fi
                printf '%s\n' '{"status":"accepted"}'
            """.trimIndent(),
        )
        val runner = AcceptanceTransactionRunner(
            repositories = listOf(
                ReminderRepositoryProjection(
                    repositoryId = repositoryId,
                    displayName = "Acceptance repository",
                    webUrl = URI("https://example.invalid/repositories/acceptance"),
                ),
            ),
            actionItems = listOf(
                ReminderActionItemProjection(
                    actionItemId = ActionItemId("ai_acceptance_reminder"),
                    repositoryId = repositoryId,
                    activityVersion = ActivityVersion("av_acceptance_reminder"),
                ),
            ),
        )
        val deliveredAfterCommit = mutableListOf<NotificationRequest>()
        val processAdapter = DesktopNotificationsProcessAdapter(executable)
        val sender = NotificationSender { request ->
            check(!runner.isTransactionActive) { "Notification delivery escaped the post-commit boundary" }
            deliveredAfterCommit += request
            processAdapter.send(request)
        }
        val dispatch = DispatchNotificationsService(runner, sender, NotificationRetryPolicy(), clock)
        val postCommit = ImmediatePostCommitNotificationDispatcher(dispatch)
        val draft = policy.createIntents(
            listOf(
                NotificationTransitionFact.ActionableActivity(
                    repositoryId = repositoryId,
                    repositoryDisplayName = "Repository $RAW_MARKER",
                    repositoryWebUrl = URI("https://example.invalid/repositories/acceptance"),
                    pullRequestId = PullRequestId("pr_acceptance"),
                    pullRequestNumber = 17,
                    pullRequestTitle = "Acceptance pull request",
                    pullRequestWebUrl = URI("https://example.invalid/pull-requests/17"),
                    actionItemId = ActionItemId("ai_acceptance"),
                    activityVersion = ActivityVersion("av_acceptance"),
                    createdAt = clock.instant(),
                ),
            ),
        ).single()
        val intentId = NotificationIntentId("ni_acceptance")
        val stored = StoredNotificationIntent(
            id = intentId,
            request = draft.request,
            createdAt = draft.createdAt,
            state = NotificationIntentState.PENDING,
            attemptCount = 0,
            nextAttemptAt = draft.createdAt,
            lease = null,
        )

        val insertedId = runner.inTransaction {
            assertTrue(runner.isTransactionActive)
            assertTrue(deliveredAfterCommit.isEmpty())
            (notificationIntentStore.insertIfAbsent(stored) as NotificationIntentInsertResult.Inserted).intent.id
        }
        assertEquals(1, runner.commitCount)
        assertTrue(deliveredAfterCommit.isEmpty())

        postCommit.dispatchCommitted(listOf(insertedId))

        val failed = requireNotNull(runner.intent(intentId))
        assertEquals(NotificationIntentState.PENDING, failed.state)
        assertEquals(1, failed.attemptCount)
        assertEquals(clock.instant().plus(Duration.ofMinutes(1)), failed.nextAttemptAt)
        assertEquals(stored.request, failed.request)
        assertEquals(
            NotificationDeliveryResult.Failed(
                NotificationDeliveryFailureCategory.DEPENDENCY_UNAVAILABLE,
                ambiguous = false,
            ),
            runner.attempts(intentId).single().result,
        )

        clock.advance(Duration.ofMinutes(1))
        val retry = RetryPendingNotificationsService(runner, dispatch, clock)()

        assertEquals(1, retry.acceptedCount)
        val accepted = requireNotNull(runner.intent(intentId))
        assertEquals(NotificationIntentState.ACCEPTED, accepted.state)
        assertEquals(2, accepted.attemptCount)
        assertEquals(null, accepted.nextAttemptAt)
        assertEquals(stored.request, accepted.request)
        assertEquals(
            listOf(
                NotificationDeliveryFailureCategory.DEPENDENCY_UNAVAILABLE,
                null,
            ),
            runner.attempts(intentId).map { (it.result as? NotificationDeliveryResult.Failed)?.category },
        )
        assertEquals(listOf(stored.request, stored.request), deliveredAfterCommit)
        val firstTwoInvocations = Files.readAllLines(argumentsFile, UTF_8)
        val expectedArguments = listOf(
            "send",
            "--delivery-key",
            stored.request.deliveryKey.value,
            "--title",
            stored.request.title,
            "--body",
            stored.request.body,
            "--open-url",
            requireNotNull(stored.request.openUrl).toString(),
            "--sound",
            "default",
        )
        assertEquals(expectedArguments + expectedArguments, firstTwoInvocations)
        assertEquals(0, firstTwoInvocations.size % 2)
        assertEquals(
            firstTwoInvocations.take(firstTwoInvocations.size / 2),
            firstTwoInvocations.drop(firstTwoInvocations.size / 2),
            "retry must preserve the direct executable argv exactly",
        )

        val reminders = SendDueRemindersService(runner, clock, policy, postCommit)
        val firstReminderIds = reminders()
        val repeatedReminderIds = reminders()

        assertEquals(1, firstReminderIds.size)
        assertTrue(repeatedReminderIds.isEmpty())
        assertEquals(NotificationIntentState.ACCEPTED, runner.intent(firstReminderIds.single())?.state)
        assertEquals(2, runner.intents().count { it.request.deliveryKey.value.startsWith("reminder:") || it.id == intentId })

        val nonLiveOutputs = listOf(
            failed.state.toString(),
            accepted.state.toString(),
            runner.attempts(intentId).toString(),
            retry.toString(),
            runner.transactions().toString(),
        )
        nonLiveOutputs.forEach { output -> assertFalse(output.contains(RAW_MARKER), output) }
    }

    @Test
    fun `scheduled bridges and owned processes close without leaking private diagnostics or live workers`(
        @TempDir directory: Path,
    ) = runBlocking {
        val calls = mutableListOf<String>()
        val scheduledUseCases = scheduledUseCases(calls)
        STABLE_USE_CASE_KEYS.forEach { key -> scheduledUseCases.operation(key)() }
        assertEquals(STABLE_USE_CASE_KEYS, calls)

        val existingThreadIds = Thread.getAllStackTraces().keys.mapTo(mutableSetOf()) { it.threadId() }
        val scheduler = QuartzApplicationScheduler.create(scheduledUseCases(mutableListOf()), Duration.ofSeconds(1))
        val stoppedHealth = scheduler.health()
        try {
            scheduler.start()
            assertEquals("scheduler-running", scheduler.health().safeCode)
        } finally {
            scheduler.close()
            scheduler.close()
        }
        assertEquals("scheduler-stopped", stoppedHealth.safeCode)
        assertEquals("scheduler-stopped", scheduler.health().safeCode)
        awaitNoNewSchedulerThread(existingThreadIds)

        val timeoutParentPidFile = directory.resolve("timeout-parent.pid")
        val timeoutChildPidFile = directory.resolve("timeout-child.pid")
        val timeoutExecutable = processTreeExecutable(directory, timeoutParentPidFile, timeoutChildPidFile)
        val timeoutProcess = ProcessBuilder(listOf(timeoutExecutable.toString())).start()
        val timedOut = try {
            val timeoutParentPid = awaitPid(timeoutParentPidFile)
            val timeoutChildPid = awaitPid(timeoutChildPidFile)
            BoundedProcessCapture().capture(timeoutProcess, Duration.ofMillis(40)).also {
                assertTrue(it is NotificationProcessResult.TimedOut)
                awaitDead(timeoutParentPid)
                awaitDead(timeoutChildPid)
            }
        } finally {
            stopProcessIfAlive(timeoutProcess)
            stopPidIfPresent(timeoutChildPidFile)
            stopPidIfPresent(timeoutParentPidFile)
        }

        val cancellationParentPidFile = directory.resolve("cancellation-parent.pid")
        val cancellationChildPidFile = directory.resolve("cancellation-child.pid")
        val cancellationExecutable = processTreeExecutable(
            directory,
            cancellationParentPidFile,
            cancellationChildPidFile,
        )
        val cancellationResult = CompletableDeferred<NotificationDeliveryResult>()
        val delivery = async {
            cancellationResult.complete(
                DesktopNotificationsProcessAdapter(cancellationExecutable).send(privateRequest()),
            )
        }
        try {
            val cancellationParentPid = awaitPid(cancellationParentPidFile)
            val cancellationChildPid = awaitPid(cancellationChildPidFile)
            delivery.cancelAndJoin()
            assertEquals(
                NotificationDeliveryResult.Failed(
                    NotificationDeliveryFailureCategory.AMBIGUOUS_PROCESS_FAILURE,
                    ambiguous = true,
                ),
                withTimeout(5_000) { cancellationResult.await() },
            )
            awaitDead(cancellationParentPid)
            awaitDead(cancellationChildPid)
        } finally {
            delivery.cancelAndJoin()
            stopPidIfPresent(cancellationChildPidFile)
            stopPidIfPresent(cancellationParentPidFile)
        }

        val unsupported = assertThrows(IllegalArgumentException::class.java) {
            scheduledUseCases.operation("unsupported-$RAW_MARKER")
        }
        val privateOutputs = listOf(
            unsupported.stackTraceToString(),
            stoppedHealth.toString(),
            scheduler.health().toString(),
            timedOut.toString(),
            cancellationResult.await().toString(),
        )
        privateOutputs.forEach { output -> assertFalse(output.contains(RAW_MARKER), output) }
    }

    private fun scheduledUseCases(calls: MutableList<String>) = ScheduledUseCases(
        refreshAllRepositories = RefreshAllRepositories {
            calls += ScheduledUseCases.REFRESH_ALL_REPOSITORIES
            RefreshAllRepositoriesResult(emptyList())
        },
        retryPendingNotifications = RetryPendingNotifications {
            calls += ScheduledUseCases.RETRY_PENDING_NOTIFICATIONS
            emptyDispatchSummary()
        },
        sendDueReminders = SendDueReminders {
            calls += ScheduledUseCases.SEND_DUE_REMINDERS
            emptyList()
        },
        pruneInactivePullRequests = PruneInactivePullRequests {
            calls += ScheduledUseCases.PRUNE_INACTIVE_PULL_REQUESTS
            PruneInactivePullRequestsResult(0, Instant.EPOCH)
        },
    )

    private fun emptyDispatchSummary() = NotificationDispatchSummary(emptyList(), 0, 0, 0)

    private fun privateRequest() = NotificationRequest(
        deliveryKey = com.mindtable.bitbuckethelper.application.model.NotificationDeliveryKey("private-cancellation"),
        title = RAW_MARKER,
        body = RAW_MARKER,
        openUrl = URI("https://example.invalid/$RAW_MARKER"),
        sound = NotificationSound.DEFAULT,
    )

    private fun processTreeExecutable(directory: Path, parentPidFile: Path, childPidFile: Path): Path =
        FakeDesktopNotificationsExecutable.create(
            directory,
            """
                sleep 60 &
                child_pid="${'$'}!"
                printf '%s' "${'$'}${'$'}" > '${parentPidFile.toAbsolutePath()}'
                printf '%s' "${'$'}child_pid" > '${childPidFile.toAbsolutePath()}'
                wait "${'$'}child_pid"
            """.trimIndent(),
        )

    private suspend fun awaitPid(path: Path): Long = withTimeout(5_000) {
        while (!path.exists() || Files.readString(path).isBlank()) delay(10)
        Files.readString(path).trim().toLong()
    }

    private suspend fun awaitDead(pid: Long) = withTimeout(5_000) {
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10)
    }

    private fun stopPidIfAlive(pid: Long) {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        if (handle.isAlive) handle.destroyForcibly()
        handle.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun stopPidIfPresent(path: Path) {
        if (!Files.exists(path)) return
        val pid = Files.readString(path, UTF_8).trim().toLongOrNull() ?: return
        stopPidIfAlive(pid)
    }

    private fun stopProcessIfAlive(process: Process) {
        if (!process.isAlive) return
        process.destroyForcibly()
        check(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            "Acceptance process fixture did not terminate"
        }
    }

    private suspend fun awaitNoNewSchedulerThread(existingThreadIds: Set<Long>) = withTimeout(5_000) {
        while (
            Thread.getAllStackTraces().keys.any {
                it.isAlive && it.threadId() !in existingThreadIds && it.name.contains("bitbucket-helper-application-")
            }
        ) {
            delay(10)
        }
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = checkNotNull(
            takeIf { zone == ZoneOffset.UTC },
        ) { "Acceptance clock supports UTC only" }
        override fun instant(): Instant = now
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    private class AcceptanceTransactionRunner(
        repositories: List<ReminderRepositoryProjection>,
        actionItems: List<ReminderActionItemProjection>,
    ) : ApplicationTransactionRunner {
        private val mutex = Mutex()
        private val transactionMarker = ThreadLocal<Boolean>()
        private var committed = State(
            intents = mutableMapOf(),
            attempts = mutableMapOf(),
            repositories = repositories,
            actionItems = actionItems,
        )
        var commitCount: Int = 0
            private set
        val isTransactionActive: Boolean
            get() = transactionMarker.get() == true

        override suspend fun <T> inTransaction(block: suspend ApplicationTransaction.() -> T): T = mutex.withLock {
            val working = committed.copyForTransaction()
            val operations = mutableListOf<String>()
            val transaction = Transaction(NotificationStore(working, operations), ProjectionStore(working))
            val result = withContext(transactionMarker.asContextElement(true)) { block(transaction) }
            committed = working.also { it.transactions += operations.toList() }
            commitCount += 1
            result
        }

        suspend fun intent(id: NotificationIntentId) = mutex.withLock { committed.intents[id] }
        suspend fun intents() = mutex.withLock { committed.intents.values.toList() }
        suspend fun attempts(id: NotificationIntentId) = mutex.withLock { committed.attempts[id].orEmpty().toList() }
        suspend fun transactions() = mutex.withLock { committed.transactions.toList() }

        private inner class NotificationStore(
            private val state: State,
            private val operations: MutableList<String>,
        ) : NotificationIntentStore {
            override suspend fun insertIfAbsent(intent: StoredNotificationIntent): NotificationIntentInsertResult {
                operations += "insertIfAbsent:${intent.id.value}"
                val existing = state.intents[intent.id]
                if (existing != null) return NotificationIntentInsertResult.Existing(existing)
                state.intents[intent.id] = intent
                return NotificationIntentInsertResult.Inserted(intent)
            }

            override suspend fun find(id: NotificationIntentId): StoredNotificationIntent? {
                operations += "find:${id.value}"
                return state.intents[id]
            }

            override suspend fun findDue(now: Instant, limit: Int): List<StoredNotificationIntent> {
                operations += "findDue"
                return state.intents.values
                    .filter { it.isDueAt(now) }
                    .sortedWith(compareBy({ it.createdAt }, { it.id.value }))
                    .take(limit)
            }

            override suspend fun tryClaim(
                id: NotificationIntentId,
                owner: String,
                acquiredAt: Instant,
                expiresAt: Instant,
            ): StoredNotificationIntent? {
                operations += "tryClaim:${id.value}"
                val intent = state.intents[id]?.takeIf { it.isDueAt(acquiredAt) } ?: return null
                return intent.copy(lease = NotificationLease(owner, acquiredAt, expiresAt)).also {
                    state.intents[id] = it
                }
            }

            override suspend fun releaseClaim(id: NotificationIntentId, owner: String): Boolean {
                operations += "releaseClaim:${id.value}"
                val intent = state.intents[id] ?: return false
                if (intent.lease?.owner != owner) return false
                state.intents[id] = intent.copy(lease = null)
                return true
            }

            override suspend fun completeAttempt(
                id: NotificationIntentId,
                owner: String,
                completion: NotificationAttemptCompletion,
            ): Boolean {
                operations += "completeAttempt:${id.value}"
                val intent = state.intents[id] ?: return false
                if (intent.lease?.owner != owner || completion.attempt.intentId != id) return false
                state.attempts.getOrPut(id, ::mutableListOf) += completion.attempt
                state.intents[id] = intent.copy(
                    state = completion.resultingState,
                    attemptCount = completion.attempt.attemptNumber,
                    nextAttemptAt = completion.nextAttemptAt,
                    lease = null,
                )
                return true
            }

            override suspend fun listAttempts(id: NotificationIntentId): List<StoredNotificationAttempt> {
                operations += "listAttempts:${id.value}"
                return state.attempts[id].orEmpty().toList()
            }
        }

        private class ProjectionStore(private val state: State) : ReminderProjectionStore {
            override suspend fun listRepositoriesWithActionableItems(): List<ReminderRepositoryProjection> =
                state.repositories

            override suspend fun listActionableItems(repositoryId: RepositoryId): List<ReminderActionItemProjection> =
                state.actionItems.filter { it.repositoryId == repositoryId }
        }

        private class Transaction(
            override val notificationIntentStore: NotificationIntentStore,
            override val reminderProjectionStore: ReminderProjectionStore,
        ) : ApplicationTransaction {
            override val configurationStore: ConfigurationStore
                get() = error("Acceptance transaction does not expose configuration")
            override val pullRequestStore: PullRequestStore
                get() = error("Acceptance transaction does not expose pull requests")
            override val actionItemStore: ActionItemStore
                get() = error("Acceptance transaction does not expose action items")
            override val synchronizationCheckpointStore: SynchronizationCheckpointStore
                get() = error("Acceptance transaction does not expose synchronization checkpoints")
        }

        private data class State(
            val intents: MutableMap<NotificationIntentId, StoredNotificationIntent>,
            val attempts: MutableMap<NotificationIntentId, MutableList<StoredNotificationAttempt>>,
            val repositories: List<ReminderRepositoryProjection>,
            val actionItems: List<ReminderActionItemProjection>,
            val transactions: MutableList<List<String>> = mutableListOf(),
        ) {
            fun copyForTransaction() = State(
                intents = intents.toMutableMap(),
                attempts = attempts.mapValuesTo(mutableMapOf()) { (_, values) -> values.toMutableList() },
                repositories = repositories,
                actionItems = actionItems,
                transactions = transactions.toMutableList(),
            )
        }

        private fun StoredNotificationIntent.isDueAt(now: Instant): Boolean =
            state == NotificationIntentState.PENDING &&
                (nextAttemptAt == null || !nextAttemptAt.isAfter(now)) &&
                (lease == null || !lease.expiresAt.isAfter(now))
    }

    private companion object {
        const val RAW_MARKER = "RAW-PRIVATE-MARKER-9"
        val STABLE_USE_CASE_KEYS = listOf(
            ScheduledUseCases.REFRESH_ALL_REPOSITORIES,
            ScheduledUseCases.RETRY_PENDING_NOTIFICATIONS,
            ScheduledUseCases.SEND_DUE_REMINDERS,
            ScheduledUseCases.PRUNE_INACTIVE_PULL_REQUESTS,
        )
    }
}
