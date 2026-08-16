package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.NotificationAttemptCompletion
import com.mindtable.bitbuckethelper.application.model.NotificationIntentInsertResult
import com.mindtable.bitbuckethelper.application.model.NotificationIntentState
import com.mindtable.bitbuckethelper.application.model.NotificationRequest
import com.mindtable.bitbuckethelper.application.model.NotificationSound
import com.mindtable.bitbuckethelper.application.model.ReminderActionItemProjection
import com.mindtable.bitbuckethelper.application.model.ReminderRepositoryProjection
import com.mindtable.bitbuckethelper.application.model.StoredNotificationAttempt
import com.mindtable.bitbuckethelper.application.model.StoredNotificationIntent
import com.mindtable.bitbuckethelper.application.policy.DefaultNotificationIntentPolicy
import com.mindtable.bitbuckethelper.application.port.outbound.ActionItemStore
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransaction
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.application.port.outbound.ConfigurationStore
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationIntentStore
import com.mindtable.bitbuckethelper.application.port.outbound.PostCommitNotificationDispatcher
import com.mindtable.bitbuckethelper.application.port.outbound.PullRequestStore
import com.mindtable.bitbuckethelper.application.port.outbound.ReminderProjectionStore
import com.mindtable.bitbuckethelper.application.port.outbound.SynchronizationCheckpointStore
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SendDueRemindersServiceTest {
    @Test
    fun `no actionable items create no reminder and trigger no dispatch`() = runBlocking {
        // Catches a reminder run that invents an intent or dispatches without actionable repository projections.
        val transactions = ReminderTransactions()
        val dispatcher = RecordingDispatcher(transactions)

        val result = service(transactions, dispatcher)()

        assertEquals(emptyList<NotificationIntentId>(), result)
        assertEquals(emptyList<StoredNotificationIntent>(), transactions.committedIntents())
        assertEquals(emptyList<List<NotificationIntentId>>(), dispatcher.calls)
    }

    @Test
    fun `multiple actionable items in one repository create one exact safe reminder`() = runBlocking {
        // Catches per-item reminders, wrong counts, unsafe payload mapping, or a non-UTC-hour delivery identity.
        val transactions = ReminderTransactions(
            repositories = listOf(repository("repo_alpha", "Alpha", "https://bitbucket.org/acme/alpha")),
            actionItems = listOf(
                actionItem("ai_RAW_ACTOR_BODY_MARKER_1", "repo_alpha"),
                actionItem("ai_alpha_2", "repo_alpha"),
            ),
        )
        val dispatcher = RecordingDispatcher(transactions)

        val result = service(transactions, dispatcher)()

        val stored = transactions.committedIntents().single()
        assertEquals(listOf(stored.id), result)
        assertEquals(listOf(result), dispatcher.calls)
        assertEquals(
            NotificationRequest(
                deliveryKey = com.mindtable.bitbuckethelper.application.model.NotificationDeliveryKey(
                    "reminder:repo_alpha:20260816T09Z",
                ),
                title = "Bitbucket Helper reminder",
                body = "Alpha: 2 items still need attention",
                openUrl = URI("https://bitbucket.org/acme/alpha"),
                sound = NotificationSound.DEFAULT,
            ),
            stored.request,
        )
        assertEquals(Instant.parse("2026-08-16T09:00:00Z"), stored.createdAt)
        assertEquals(NotificationIntentState.PENDING, stored.state)
        assertEquals(0, stored.attemptCount)
        assertEquals(Instant.parse("2026-08-16T09:00:00Z"), stored.nextAttemptAt)
        assertEquals(null, stored.lease)
        assertEquals(false, stored.request.toString().contains("RAW_ACTOR_BODY_MARKER"))
    }

    @Test
    fun `two repositories create one grouped reminder each in stable repository order`() = runBlocking {
        // Catches dropping a repository, combining repositories, or leaking projection iteration order.
        val transactions = ReminderTransactions(
            repositories = listOf(
                repository("repo_beta", "Beta", "https://bitbucket.org/acme/beta"),
                repository("repo_alpha", "Alpha", "https://bitbucket.org/acme/alpha"),
            ),
            actionItems = listOf(
                actionItem("ai_beta_1", "repo_beta"),
                actionItem("ai_alpha_1", "repo_alpha"),
                actionItem("ai_alpha_2", "repo_alpha"),
            ),
        )
        val dispatcher = RecordingDispatcher(transactions)

        val result = service(transactions, dispatcher)()

        assertEquals(
            listOf(
                "reminder:repo_alpha:20260816T09Z",
                "reminder:repo_beta:20260816T09Z",
            ),
            transactions.committedIntents().map { it.request.deliveryKey.value },
        )
        assertEquals(
            listOf(
                "Alpha: 2 items still need attention",
                "Beta: 1 item still need attention",
            ),
            transactions.committedIntents().map { it.request.body },
        )
        assertEquals(transactions.committedIntents().map { it.id }, result)
        assertEquals(listOf(result), dispatcher.calls)
    }

    @Test
    fun `acknowledged closed inactive and removed repository items are excluded`() = runBlocking {
        // Catches bypassing the actionable reminder projection with broader aggregate or repository reads.
        val transactions = ReminderTransactions(
            repositories = listOf(
                repository("repo_alpha", "Alpha", "https://bitbucket.org/acme/alpha"),
                repository("repo_removed", "Removed", "https://bitbucket.org/acme/removed", removed = true),
            ),
            actionItems = listOf(
                actionItem("ai_alpha_actionable", "repo_alpha"),
                actionItem("ai_alpha_acknowledged", "repo_alpha", acknowledged = true),
                actionItem("ai_alpha_closed", "repo_alpha", closed = true),
                actionItem("ai_alpha_inactive", "repo_alpha", pullRequestActive = false),
                actionItem("ai_removed_actionable", "repo_removed"),
            ),
        )
        val dispatcher = RecordingDispatcher(transactions)

        service(transactions, dispatcher)()

        assertEquals(
            listOf("reminder:repo_alpha:20260816T09Z"),
            transactions.committedIntents().map { it.request.deliveryKey.value },
        )
        assertEquals(
            listOf("Alpha: 1 item still need attention"),
            transactions.committedIntents().map { it.request.body },
        )
    }

    @Test
    fun `same UTC hour is durable no-op while next UTC hour creates a new reminder`() = runBlocking {
        // Catches ID-based deduplication, local-time buckets, or re-dispatching an existing same-hour intent.
        val transactions = ReminderTransactions(
            repositories = listOf(repository("repo_alpha", "Alpha", "https://bitbucket.org/acme/alpha")),
            actionItems = listOf(actionItem("ai_alpha_1", "repo_alpha")),
        )
        val dispatcher = RecordingDispatcher(transactions)

        val first = service(transactions, dispatcher, Instant.parse("2026-08-16T09:01:00Z"))()
        val repeated = service(transactions, dispatcher, Instant.parse("2026-08-16T09:59:59Z"))()
        val nextHour = service(transactions, dispatcher, Instant.parse("2026-08-16T10:00:00Z"))()

        assertEquals(1, first.size)
        assertEquals(emptyList<NotificationIntentId>(), repeated)
        assertEquals(1, nextHour.size)
        assertEquals(
            listOf(
                "reminder:repo_alpha:20260816T09Z",
                "reminder:repo_alpha:20260816T10Z",
            ),
            transactions.committedIntents().map { it.request.deliveryKey.value },
        )
        assertEquals(listOf(first, nextHour), dispatcher.calls)
    }

    @Test
    fun `transaction failure rolls back every reminder and prevents dispatch`() = runBlocking {
        // Catches partial intent publication or dispatch from inside a transaction that later rolls back.
        val transactions = ReminderTransactions(
            repositories = listOf(
                repository("repo_alpha", "Alpha", "https://bitbucket.org/acme/alpha"),
                repository("repo_beta", "Beta", "https://bitbucket.org/acme/beta"),
            ),
            actionItems = listOf(
                actionItem("ai_alpha_1", "repo_alpha"),
                actionItem("ai_beta_1", "repo_beta"),
            ),
            failOnDeliveryKey = "reminder:repo_beta:20260816T09Z",
        )
        val dispatcher = RecordingDispatcher(transactions)

        val failure = runCatching { service(transactions, dispatcher)() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("intent insert failed", failure?.message)
        assertEquals(emptyList<StoredNotificationIntent>(), transactions.committedIntents())
        assertEquals(emptyList<List<NotificationIntentId>>(), dispatcher.calls)
    }

    @Test
    fun `dispatcher failure happens after the reminder transaction is committed`() = runBlocking {
        // Catches external dispatch inside the creating transaction or accidental rollback after commit.
        val transactions = ReminderTransactions(
            repositories = listOf(repository("repo_alpha", "Alpha", "https://bitbucket.org/acme/alpha")),
            actionItems = listOf(actionItem("ai_alpha_1", "repo_alpha")),
        )
        val dispatcher = PostCommitNotificationDispatcher { ids ->
            check(!transactions.isTransactionActive)
            check(ids.single() == transactions.committedIntents().single().id)
            throw IllegalStateException("dispatcher failed")
        }

        val failure = runCatching { service(transactions, dispatcher)() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("dispatcher failed", failure?.message)
        assertEquals(
            listOf("reminder:repo_alpha:20260816T09Z"),
            transactions.committedIntents().map { it.request.deliveryKey.value },
        )
    }

    private fun service(
        transactions: ReminderTransactions,
        dispatcher: PostCommitNotificationDispatcher,
        now: Instant = Instant.parse("2026-08-16T09:42:17Z"),
    ) = SendDueRemindersService(
        transactions = transactions,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        intentPolicy = DefaultNotificationIntentPolicy(),
        postCommitDispatcher = dispatcher,
    )

    private class RecordingDispatcher(
        private val transactions: ReminderTransactions,
    ) : PostCommitNotificationDispatcher {
        val calls = mutableListOf<List<NotificationIntentId>>()

        override suspend fun dispatchCommitted(intentIds: List<NotificationIntentId>) {
            check(!transactions.isTransactionActive) { "dispatch must happen after commit" }
            check(intentIds.all { transactions.committedIntent(it) != null }) {
                "dispatcher received an uncommitted intent"
            }
            calls += intentIds
        }
    }

    private class ReminderTransactions(
        private val repositories: List<TestRepository> = emptyList(),
        private val actionItems: List<TestActionItem> = emptyList(),
        private val failOnDeliveryKey: String? = null,
    ) : ApplicationTransactionRunner {
        private var transactionActive = false
        private var intents = linkedMapOf<NotificationIntentId, StoredNotificationIntent>()

        val isTransactionActive: Boolean get() = transactionActive

        override suspend fun <T> inTransaction(block: suspend ApplicationTransaction.() -> T): T {
            check(!transactionActive)
            val working = LinkedHashMap(intents)
            transactionActive = true
            return try {
                val result = block(ReminderTransaction(working))
                intents = working
                result
            } finally {
                transactionActive = false
            }
        }

        fun committedIntents(): List<StoredNotificationIntent> = intents.values.toList()

        fun committedIntent(id: NotificationIntentId): StoredNotificationIntent? = intents[id]

        private inner class ReminderTransaction(
            working: LinkedHashMap<NotificationIntentId, StoredNotificationIntent>,
        ) : ApplicationTransaction {
            override val notificationIntentStore: NotificationIntentStore = InMemoryIntentStore(
                working,
                failOnDeliveryKey,
            )
            override val reminderProjectionStore: ReminderProjectionStore = InMemoryReminderProjectionStore(
                repositories,
                actionItems,
            )
            override val configurationStore: ConfigurationStore
                get() = error("not used by reminder service")
            override val pullRequestStore: PullRequestStore
                get() = error("not used by reminder service")
            override val actionItemStore: ActionItemStore
                get() = error("not used by reminder service")
            override val synchronizationCheckpointStore: SynchronizationCheckpointStore
                get() = error("not used by reminder service")
        }
    }

    private class InMemoryIntentStore(
        private val intents: LinkedHashMap<NotificationIntentId, StoredNotificationIntent>,
        private val failOnDeliveryKey: String?,
    ) : NotificationIntentStore {
        override suspend fun insertIfAbsent(intent: StoredNotificationIntent): NotificationIntentInsertResult {
            if (intent.request.deliveryKey.value == failOnDeliveryKey) {
                throw IllegalStateException("intent insert failed")
            }
            val existing = intents.values.firstOrNull { it.request.deliveryKey == intent.request.deliveryKey }
            if (existing != null) return NotificationIntentInsertResult.Existing(existing)
            intents[intent.id] = intent
            return NotificationIntentInsertResult.Inserted(intent)
        }

        override suspend fun find(id: NotificationIntentId): StoredNotificationIntent? = intents[id]

        override suspend fun findDue(now: Instant, limit: Int): List<StoredNotificationIntent> =
            error("not used by reminder service")

        override suspend fun tryClaim(
            id: NotificationIntentId,
            owner: String,
            acquiredAt: Instant,
            expiresAt: Instant,
        ): StoredNotificationIntent? = error("not used by reminder service")

        override suspend fun releaseClaim(id: NotificationIntentId, owner: String): Boolean =
            error("not used by reminder service")

        override suspend fun completeAttempt(
            id: NotificationIntentId,
            owner: String,
            completion: NotificationAttemptCompletion,
        ): Boolean = error("not used by reminder service")

        override suspend fun listAttempts(id: NotificationIntentId): List<StoredNotificationAttempt> =
            error("not used by reminder service")
    }

    private class InMemoryReminderProjectionStore(
        private val repositories: List<TestRepository>,
        private val actionItems: List<TestActionItem>,
    ) : ReminderProjectionStore {
        override suspend fun listRepositoriesWithActionableItems(): List<ReminderRepositoryProjection> = repositories
            .filterNot(TestRepository::removed)
            .filter { repository -> eligibleItems(repository.id).isNotEmpty() }
            .map { repository ->
                ReminderRepositoryProjection(repository.id, repository.displayName, repository.webUrl)
            }

        override suspend fun listActionableItems(repositoryId: RepositoryId): List<ReminderActionItemProjection> =
            eligibleItems(repositoryId).map { item ->
                ReminderActionItemProjection(item.id, item.repositoryId, item.activityVersion)
            }

        private fun eligibleItems(repositoryId: RepositoryId): List<TestActionItem> = actionItems.filter { item ->
            item.repositoryId == repositoryId && !item.acknowledged && !item.closed && item.pullRequestActive
        }
    }

    private data class TestRepository(
        val id: RepositoryId,
        val displayName: String,
        val webUrl: URI,
        val removed: Boolean = false,
    )

    private data class TestActionItem(
        val id: ActionItemId,
        val repositoryId: RepositoryId,
        val activityVersion: ActivityVersion,
        val acknowledged: Boolean = false,
        val closed: Boolean = false,
        val pullRequestActive: Boolean = true,
    )

    private fun repository(
        id: String,
        displayName: String,
        webUrl: String,
        removed: Boolean = false,
    ) = TestRepository(RepositoryId(id), displayName, URI(webUrl), removed)

    private fun actionItem(
        id: String,
        repositoryId: String,
        acknowledged: Boolean = false,
        closed: Boolean = false,
        pullRequestActive: Boolean = true,
    ) = TestActionItem(
        id = ActionItemId(id),
        repositoryId = RepositoryId(repositoryId),
        activityVersion = ActivityVersion("av_${id.removePrefix("ai_")}"),
        acknowledged = acknowledged,
        closed = closed,
        pullRequestActive = pullRequestActive,
    )
}
