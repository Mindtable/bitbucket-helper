package com.mindtable.bitbuckethelper.application.contract

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.domain.shared.*
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

abstract class ApplicationPersistenceContract {
    private lateinit var persistence: ApplicationTransactionRunner

    protected abstract fun createPersistence(): ApplicationTransactionRunner

    @BeforeEach fun openPersistence() { persistence = createPersistence() }
    @AfterEach fun closePersistence() { (persistence as? AutoCloseable)?.close() }

    @Test fun `transaction commits every store and rollback discards every store`() = runTest {
        val intent = intent()
        persistence.inTransaction {
            configurationStore.save(configuration())
            pullRequestStore.save(pullRequest())
            actionItemStore.save(actionItem())
            synchronizationCheckpointStore.save(synchronization())
            notificationIntentStore.insertIfAbsent(intent)
        }
        persistence.inTransaction {
            assertNotNull(configurationStore.find()); assertNotNull(pullRequestStore.find(pullRequestA))
            assertNotNull(actionItemStore.find(actionItemA)); assertNotNull(synchronizationCheckpointStore.find(repositoryA))
            assertEquals(intent, notificationIntentStore.find(intent.id))
        }
        assertThrows(IllegalStateException::class.java) {
            runTest { persistence.inTransaction { configurationStore.save(configuration(emptyList())); pullRequestStore.delete(pullRequestA); error("rollback") } }
        }
        persistence.inTransaction { assertEquals(2, configurationStore.find()!!.repositories.size); assertNotNull(pullRequestStore.find(pullRequestA)) }
    }

    @Test fun `lists are stable ordered and saves are idempotent`() = runTest {
        persistence.inTransaction {
            pullRequestStore.save(pullRequest(pullRequestB, repositoryA, 2)); pullRequestStore.save(pullRequest()); pullRequestStore.save(pullRequest())
            actionItemStore.save(actionItem(actionItemB, pullRequestB, repositoryA, versionB)); actionItemStore.save(actionItem()); actionItemStore.save(actionItem())
            synchronizationCheckpointStore.save(synchronization(repositoryB)); synchronizationCheckpointStore.save(synchronization())
        }
        persistence.inTransaction {
            assertEquals(listOf(pullRequestA, pullRequestB), pullRequestStore.listByRepository(repositoryA, true).map { it.id })
            assertEquals(listOf(actionItemA, actionItemB), actionItemStore.listActionable().map { it.id })
            assertEquals(listOf(repositoryA, repositoryB), synchronizationCheckpointStore.list().map { it.repositoryId })
        }
    }

    @Test fun `authoritative missing deactivation retains metadata and is idempotent`() = runTest {
        val at = t0.plusSeconds(60)
        persistence.inTransaction { pullRequestStore.save(pullRequest()); pullRequestStore.save(pullRequest(pullRequestB, repositoryA, 2)) }
        persistence.inTransaction {
            assertEquals(listOf(pullRequestB), pullRequestStore.markMissingInactive(repositoryA, setOf(pullRequestA), at).map { it.id })
            assertTrue(pullRequestStore.markMissingInactive(repositoryA, setOf(pullRequestA), at).isEmpty())
            val missing = pullRequestStore.find(pullRequestB)!!
            assertFalse(missing.active); assertEquals(at, missing.inactiveAt); assertEquals("PR 2", missing.title)
        }
    }

    @Test fun `acknowledgment is exact compare and set`() = runTest {
        val acknowledgedAt = t0.plusSeconds(10)
        persistence.inTransaction {
            assertEquals(StoredAcknowledgmentResult.Missing, actionItemStore.acknowledge(actionItemA, versionA, acknowledgedAt))
            actionItemStore.save(actionItem())
            assertTrue(actionItemStore.acknowledge(actionItemA, versionB, acknowledgedAt) is StoredAcknowledgmentResult.VersionMismatch)
            assertTrue(actionItemStore.acknowledge(actionItemA, versionA, acknowledgedAt) is StoredAcknowledgmentResult.Updated)
            assertTrue(actionItemStore.acknowledge(actionItemA, versionA, acknowledgedAt.plusSeconds(1)) is StoredAcknowledgmentResult.AlreadyApplied)
            actionItemStore.save(actionItem(state = ActionItemState.CLOSED))
            assertTrue(actionItemStore.acknowledge(actionItemA, versionA, acknowledgedAt) is StoredAcknowledgmentResult.NotActionable)
        }
    }

    @Test fun `notification insert due ordering and limit are deterministic`() = runTest {
        val later = intent(NotificationIntentId("ni_later"), t0.plusSeconds(2), t0.plusSeconds(2))
        val first = intent(NotificationIntentId("ni_first"), t0.plusSeconds(1), t0)
        persistence.inTransaction {
            assertTrue(notificationIntentStore.insertIfAbsent(later) is NotificationIntentInsertResult.Inserted)
            assertTrue(notificationIntentStore.insertIfAbsent(first) is NotificationIntentInsertResult.Inserted)
            assertTrue(notificationIntentStore.insertIfAbsent(first.copy(request = first.request.copy(title = "Changed"))) is NotificationIntentInsertResult.Existing)
            assertEquals(listOf(first.id), notificationIntentStore.findDue(t0.plusSeconds(5), 1).map { it.id })
            assertTrue(notificationIntentStore.findDue(t0, 0).isEmpty())
        }
    }

    @Test fun `leases enforce ownership release expiry and completion compare and set`() = runTest {
        val original = intent()
        persistence.inTransaction { notificationIntentStore.insertIfAbsent(original) }
        persistence.inTransaction {
            assertNotNull(notificationIntentStore.tryClaim(original.id, "owner-a", t0, t0.plusSeconds(10)))
            assertNull(notificationIntentStore.tryClaim(original.id, "owner-b", t0.plusSeconds(1), t0.plusSeconds(11)))
            assertFalse(notificationIntentStore.releaseClaim(original.id, "owner-b"))
            assertTrue(notificationIntentStore.releaseClaim(original.id, "owner-a"))
            assertNotNull(notificationIntentStore.tryClaim(original.id, "owner-a", t0.plusSeconds(2), t0.plusSeconds(10)))
            assertNotNull(notificationIntentStore.tryClaim(original.id, "owner-b", t0.plusSeconds(10), t0.plusSeconds(20)))
            val completion = NotificationAttemptCompletion(attempt(original.id), NotificationIntentState.ACCEPTED, null)
            assertFalse(notificationIntentStore.completeAttempt(original.id, "owner-a", completion))
            assertTrue(notificationIntentStore.completeAttempt(original.id, "owner-b", completion))
            assertFalse(notificationIntentStore.completeAttempt(original.id, "owner-b", completion))
            assertEquals(listOf(completion.attempt), notificationIntentStore.listAttempts(original.id))
            val completed = notificationIntentStore.find(original.id)!!
            assertEquals(NotificationIntentState.ACCEPTED, completed.state); assertEquals(1, completed.attemptCount); assertNull(completed.lease)
        }
    }

    @Test fun `delete cascades action items and notification attempts survive only their intent`() = runTest {
        val notification = intent()
        persistence.inTransaction {
            pullRequestStore.save(pullRequest()); actionItemStore.save(actionItem()); notificationIntentStore.insertIfAbsent(notification)
            notificationIntentStore.tryClaim(notification.id, "owner", t0, t0.plusSeconds(5))
            notificationIntentStore.completeAttempt(notification.id, "owner", NotificationAttemptCompletion(attempt(notification.id), NotificationIntentState.ACCEPTED, null))
            pullRequestStore.delete(pullRequestA)
            assertNull(pullRequestStore.find(pullRequestA)); assertTrue(actionItemStore.listByPullRequest(pullRequestA).isEmpty())
            assertEquals(1, notificationIntentStore.listAttempts(notification.id).size)
        }
    }

    @Test fun `reminder projections derive from active configured repositories pull requests and actionable items`() = runTest {
        persistence.inTransaction {
            configurationStore.save(configuration())
            pullRequestStore.save(pullRequest()); pullRequestStore.save(pullRequest(pullRequestB, repositoryB, 2, active = false, inactiveAt = t0))
            actionItemStore.save(actionItem()); actionItemStore.save(actionItem(actionItemB, pullRequestB, repositoryB, versionB))
            assertEquals(listOf(repositoryA), reminderProjectionStore.listRepositoriesWithActionableItems().map { it.repositoryId })
            assertEquals(listOf(actionItemA), reminderProjectionStore.listActionableItems(repositoryA).map { it.actionItemId })
        }
    }

    @Test fun `uncommitted state is never visible and rollback publishes nothing`() = runTest {
        val mutationReady = CompletableDeferred<Unit>()
        val allowRollback = CompletableDeferred<Unit>()
        val writer = async {
            try {
                persistence.inTransaction {
                    pullRequestStore.save(pullRequest())
                    mutationReady.complete(Unit)
                    allowRollback.await()
                    error("cancel publication")
                }
                fail("transaction should fail")
            } catch (failure: IllegalStateException) {
                assertEquals("cancel publication", failure.message)
            }
        }
        mutationReady.await()
        val reader = async { persistence.inTransaction { pullRequestStore.find(pullRequestA) } }
        assertFalse(reader.isCompleted)
        allowRollback.complete(Unit)
        writer.await()
        assertNull(reader.await())
    }

    @Test fun `concurrent transactions serialize without lost updates`() = runTest {
        (1L..20L).map { number -> async {
            persistence.inTransaction {
                pullRequestStore.save(pullRequest(PullRequestId("pr_$number"), repositoryA, number))
            }
        } }.awaitAll()
        persistence.inTransaction { assertEquals(20, pullRequestStore.listByRepository(repositoryA, true).size) }
    }

    @Test fun `closed persistence rejects work and close is idempotent`() {
        val closeable = persistence as AutoCloseable
        closeable.close(); closeable.close()
        assertThrows(IllegalStateException::class.java) { runTest { persistence.inTransaction { configurationStore.find() } } }
    }
}
