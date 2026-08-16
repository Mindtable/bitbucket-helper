package com.mindtable.bitbuckethelper.application.contract

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.domain.shared.*
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.yield
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
        val baselineConfiguration = configuration()
        val baselinePullRequest = pullRequest()
        val baselineActionItem = actionItem()
        val baselineSynchronization = synchronization()
        persistence.inTransaction {
            configurationStore.save(baselineConfiguration)
            pullRequestStore.save(baselinePullRequest)
            actionItemStore.save(baselineActionItem)
            synchronizationCheckpointStore.save(baselineSynchronization)
            notificationIntentStore.insertIfAbsent(intent)
        }
        try {
            persistence.inTransaction {
                configurationStore.save(configuration(emptyList()))
                pullRequestStore.delete(pullRequestA)
                actionItemStore.save(actionItem(actionItemB, pullRequestB, repositoryB, versionB))
                synchronizationCheckpointStore.save(synchronization(repositoryB))
                notificationIntentStore.tryClaim(intent.id, "rollback-owner", t0, t0.plusSeconds(30))
                notificationIntentStore.completeAttempt(
                    intent.id,
                    "rollback-owner",
                    NotificationAttemptCompletion(attempt(intent.id), NotificationIntentState.ACCEPTED, null),
                )
                error("rollback")
            }
            fail("transaction should fail")
        } catch (failure: IllegalStateException) {
            assertEquals("rollback", failure.message)
        }
        persistence.inTransaction {
            assertEquals(baselineConfiguration, configurationStore.find())
            assertEquals(baselinePullRequest, pullRequestStore.find(pullRequestA))
            assertEquals(baselineActionItem, actionItemStore.find(actionItemA))
            assertEquals(listOf(baselineSynchronization), synchronizationCheckpointStore.list())
            assertEquals(intent, notificationIntentStore.find(intent.id))
            assertTrue(notificationIntentStore.listAttempts(intent.id).isEmpty())
        }
    }

    @Test fun `cancellation rolls back all mutations`() = runTest {
        val original = intent()
        persistence.inTransaction { configurationStore.save(configuration()); pullRequestStore.save(pullRequest()); notificationIntentStore.insertIfAbsent(original) }
        try {
            persistence.inTransaction {
                configurationStore.save(configuration(emptyList()))
                pullRequestStore.delete(pullRequestA)
                notificationIntentStore.tryClaim(original.id, "owner", t0, t0.plusSeconds(10))
                notificationIntentStore.completeAttempt(
                    original.id, "owner", NotificationAttemptCompletion(attempt(original.id), NotificationIntentState.ACCEPTED, null),
                )
                throw CancellationException("cancel transaction")
            }
        } catch (failure: CancellationException) {
            assertEquals("cancel transaction", failure.message)
        }
        persistence.inTransaction {
            assertEquals(configuration(), configurationStore.find()); assertEquals(pullRequest(), pullRequestStore.find(pullRequestA))
            assertEquals(original, notificationIntentStore.find(original.id)); assertTrue(notificationIntentStore.listAttempts(original.id).isEmpty())
        }
    }

    @Test fun `saved inputs and returned lists cannot mutate persisted collections`() = runTest {
        val repositories = mutableListOf(repository(repositoryA), repository(repositoryB))
        val checks = mutableListOf(StoredReadinessCheck("review", true, null), StoredReadinessCheck("build", true, null))
        val builds = mutableListOf(
            StoredBuildObservation("a", BuildState.SUCCESSFUL, t0), StoredBuildObservation("b", BuildState.SUCCESSFUL, t0),
        )
        val storedPullRequest = pullRequest().copy(readiness = StoredReadiness.Available(2, 2, checks), builds = builds)
        persistence.inTransaction { configurationStore.save(configuration(repositories)); pullRequestStore.save(storedPullRequest); pullRequestStore.save(pullRequest(pullRequestB, repositoryA, 2)) }
        repositories.clear(); checks.clear(); builds.clear()
        persistence.inTransaction {
            assertEquals(2, configurationStore.find()!!.repositories.size)
            val returned = pullRequestStore.listByRepository(repositoryA, true)
            val first = returned.first { it.id == pullRequestA }
            clearWhenMutable(first.builds)
            clearWhenMutable((first.readiness as StoredReadiness.Available).checks)
            clearWhenMutable(returned)
        }
        persistence.inTransaction {
            assertEquals(2, pullRequestStore.listByRepository(repositoryA, true).size)
            val reread = pullRequestStore.find(pullRequestA)!!
            assertEquals(2, reread.builds.size); assertEquals(2, (reread.readiness as StoredReadiness.Available).checks.size)
        }
    }

    @Test fun `synchronization failure lists are deeply detached at save and read boundaries`() = runTest {
        val retryAt = Instant.parse("2026-08-15T08:01:00.123456789Z")
        val expectedFailure = SynchronizationFailure(
            SynchronizationFailureCategory.NETWORK,
            retryable = true,
            retryAt = retryAt,
        )
        val callerOwnedFailures = mutableListOf(expectedFailure)
        val snapshot = synchronization().copy(
            problem = SynchronizationProblem.Present(
                PartialFailureMetadata(
                    attemptedCount = 1,
                    succeededCount = 0,
                    failures = callerOwnedFailures,
                ),
            ),
        )
        persistence.inTransaction { synchronizationCheckpointStore.save(snapshot) }
        callerOwnedFailures.clear()

        persistence.inTransaction {
            val returnedFailures = (
                synchronizationCheckpointStore.find(repositoryA)!!.problem as SynchronizationProblem.Present
            ).metadata.failures
            assertEquals(listOf(expectedFailure), returnedFailures)
            clearWhenMutable(returnedFailures)
        }

        persistence.inTransaction {
            val rereadFailures = (
                synchronizationCheckpointStore.list().single().problem as SynchronizationProblem.Present
            ).metadata.failures
            assertEquals(listOf(expectedFailure), rereadFailures)
        }
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

    @Test fun `stale authoritative deactivation preserves newer observations and returns only changes`() = runTest {
        val authoritativeAt = Instant.parse("2026-08-15T08:00:00Z")
        val newerObservedAt = Instant.parse("2026-08-15T08:00:00.500000000Z")
        val newer = pullRequest().copy(observedAt = newerObservedAt)
        val eligible = pullRequest(pullRequestB, repositoryA, 2).copy(
            observedAt = Instant.parse("2026-08-15T07:59:59.999999999Z"),
        )
        persistence.inTransaction {
            pullRequestStore.save(newer)
            pullRequestStore.save(eligible)

            assertEquals(
                listOf(pullRequestB),
                pullRequestStore.markMissingInactive(repositoryA, emptySet(), authoritativeAt).map { it.id },
            )
            assertEquals(newer, pullRequestStore.find(pullRequestA))
            assertEquals(
                eligible.copy(active = false, inactiveAt = authoritativeAt, observedAt = authoritativeAt),
                pullRequestStore.find(pullRequestB),
            )
        }
    }

    @Test fun `fractional inactive time after a whole-second cutoff is retained`() = runTest {
        val inactiveAt = Instant.parse("2026-08-15T08:00:00.500000000Z")
        persistence.inTransaction {
            pullRequestStore.save(
                pullRequest(active = false, inactiveAt = inactiveAt).copy(observedAt = inactiveAt),
            )

            assertTrue(
                pullRequestStore.listInactiveBefore(Instant.parse("2026-08-15T08:00:00Z")).isEmpty(),
            )
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

    @Test fun `acknowledgment store guard clamps time to current activity`() = runTest {
        val activityAt = Instant.parse("2026-08-15T08:00:00.500000000Z")
        persistence.inTransaction {
            actionItemStore.save(actionItem().copy(activityAt = activityAt, observedAt = activityAt))

            val result = assertInstanceOf(
                StoredAcknowledgmentResult.Updated::class.java,
                actionItemStore.acknowledge(
                    actionItemA,
                    versionA,
                    Instant.parse("2026-08-15T08:00:00Z"),
                ),
            )
            assertEquals(activityAt, result.snapshot.acknowledgedAt)
            assertEquals(activityAt, actionItemStore.find(actionItemA)!!.acknowledgedAt)
        }
    }

    @Test fun `notification insert due ordering and limit are deterministic`() = runTest {
        val nullLaterId = intent(NotificationIntentId("ni_null_z"), t0.plusSeconds(2), null)
        val nullFirstId = intent(NotificationIntentId("ni_null_a"), t0.plusSeconds(2), null)
        val nullEarlierCreated = intent(NotificationIntentId("ni_null_late"), t0.plusSeconds(1), null)
        val scheduledEarlier = intent(NotificationIntentId("ni_scheduled"), t0.plusSeconds(5), t0.minusSeconds(1))
        val scheduledTieZ = intent(NotificationIntentId("ni_tie_z"), t0.plusSeconds(3), t0)
        val scheduledTieA = intent(NotificationIntentId("ni_tie_a"), t0.plusSeconds(3), t0)
        val expected = listOf(nullEarlierCreated, nullFirstId, nullLaterId, scheduledEarlier, scheduledTieA, scheduledTieZ)
        persistence.inTransaction {
            expected.reversed().forEach { assertTrue(notificationIntentStore.insertIfAbsent(it) is NotificationIntentInsertResult.Inserted) }
            assertTrue(notificationIntentStore.insertIfAbsent(nullFirstId.copy(request = nullFirstId.request.copy(title = "Changed"))) is NotificationIntentInsertResult.Existing)
            assertEquals(expected.map { it.id }, notificationIntentStore.findDue(t0, 20).map { it.id })
            assertEquals(expected.take(3).map { it.id }, notificationIntentStore.findDue(t0, 3).map { it.id })
            assertTrue(notificationIntentStore.findDue(t0, 0).isEmpty())
        }
    }

    @Test fun `a fractional future notification is not due at the preceding whole second`() = runTest {
        val future = intent(
            id = NotificationIntentId("ni_fractional_future"),
            createdAt = Instant.parse("2026-08-15T08:00:00.500000000Z"),
            nextAttemptAt = Instant.parse("2026-08-15T08:00:00.500000000Z"),
        )

        persistence.inTransaction {
            notificationIntentStore.insertIfAbsent(future)

            assertTrue(notificationIntentStore.findDue(Instant.parse("2026-08-15T08:00:00Z"), 10).isEmpty())
        }
    }

    @Test fun `due notifications are ordered by exact fractional Instant chronology`() = runTest {
        val wholeSecond = intent(
            id = NotificationIntentId("ni_whole_second"),
            createdAt = Instant.parse("2026-08-15T08:00:00Z"),
            nextAttemptAt = Instant.parse("2026-08-15T08:00:00Z"),
        )
        val fractionalSecond = intent(
            id = NotificationIntentId("ni_fractional_second"),
            createdAt = Instant.parse("2026-08-15T08:00:00.500000000Z"),
            nextAttemptAt = Instant.parse("2026-08-15T08:00:00.500000000Z"),
        )

        persistence.inTransaction {
            notificationIntentStore.insertIfAbsent(fractionalSecond)
            notificationIntentStore.insertIfAbsent(wholeSecond)

            assertEquals(
                listOf(wholeSecond.id, fractionalSecond.id),
                notificationIntentStore.findDue(Instant.parse("2026-08-15T08:00:01Z"), 10).map { it.id },
            )
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
            val nextAttemptAt = t0.plusSeconds(90)
            val completion = NotificationAttemptCompletion(attempt(original.id), NotificationIntentState.PENDING, nextAttemptAt)
            assertFalse(notificationIntentStore.completeAttempt(original.id, "owner-a", completion))
            assertTrue(notificationIntentStore.completeAttempt(original.id, "owner-b", completion))
            assertFalse(notificationIntentStore.completeAttempt(original.id, "owner-b", completion))
            assertEquals(listOf(completion.attempt), notificationIntentStore.listAttempts(original.id))
            val completed = notificationIntentStore.find(original.id)!!
            assertEquals(NotificationIntentState.PENDING, completed.state); assertEquals(1, completed.attemptCount)
            assertEquals(nextAttemptAt, completed.nextAttemptAt); assertNull(completed.lease)
        }
    }

    @Test fun `a lease with a fractional future expiry cannot be stolen at the preceding whole second`() = runTest {
        val notification = intent(NotificationIntentId("ni_fractional_lease"))
        val acquiredAt = Instant.parse("2026-08-15T08:00:00Z")
        val expiresAt = Instant.parse("2026-08-15T08:00:00.500000000Z")
        persistence.inTransaction { notificationIntentStore.insertIfAbsent(notification) }

        persistence.inTransaction {
            assertNotNull(notificationIntentStore.tryClaim(notification.id, "owner-a", acquiredAt.minusSeconds(1), expiresAt))
            assertNull(notificationIntentStore.tryClaim(notification.id, "owner-b", acquiredAt, acquiredAt.plusSeconds(1)))
            assertEquals("owner-a", notificationIntentStore.find(notification.id)!!.lease!!.owner)
        }
    }

    @Test fun `notification attempt identifiers are globally unique without partial mutation`() = runTest {
        val first = intent(NotificationIntentId("ni_unique_a"))
        val second = intent(NotificationIntentId("ni_unique_b"))
        val sharedAttemptId = NotificationAttemptId("na_globally_unique")
        persistence.inTransaction {
            notificationIntentStore.insertIfAbsent(first); notificationIntentStore.insertIfAbsent(second)
            notificationIntentStore.tryClaim(first.id, "owner-a", t0, t0.plusSeconds(10))
            val firstAttempt = attempt(first.id).copy(id = sharedAttemptId)
            assertTrue(notificationIntentStore.completeAttempt(first.id, "owner-a", NotificationAttemptCompletion(firstAttempt, NotificationIntentState.PENDING, t0.plusSeconds(20))))
            notificationIntentStore.tryClaim(first.id, "owner-a", t0.plusSeconds(11), t0.plusSeconds(30))
            val beforeSameIntent = notificationIntentStore.find(first.id)
            assertFalse(notificationIntentStore.completeAttempt(first.id, "owner-a", NotificationAttemptCompletion(firstAttempt.copy(attemptNumber = 2), NotificationIntentState.ACCEPTED, null)))
            assertEquals(beforeSameIntent, notificationIntentStore.find(first.id)); assertEquals(listOf(firstAttempt), notificationIntentStore.listAttempts(first.id))
            notificationIntentStore.tryClaim(second.id, "owner-b", t0, t0.plusSeconds(10))
            val beforeOtherIntent = notificationIntentStore.find(second.id)
            val reusedElsewhere = attempt(second.id).copy(id = sharedAttemptId)
            assertFalse(notificationIntentStore.completeAttempt(second.id, "owner-b", NotificationAttemptCompletion(reusedElsewhere, NotificationIntentState.ACCEPTED, null)))
            assertEquals(beforeOtherIntent, notificationIntentStore.find(second.id)); assertTrue(notificationIntentStore.listAttempts(second.id).isEmpty())
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
        val pullRequestZ = PullRequestId("pr_zeta")
        val itemForASecond = ActionItemId("ai_zeta")
        val itemForAEarlier = ActionItemId("ai_yankee")
        val itemForZ = ActionItemId("ai_alpha")
        persistence.inTransaction {
            configurationStore.save(configuration(listOf(repository(repositoryB), repository(repositoryA))))
            pullRequestStore.save(pullRequest(pullRequestZ, repositoryA, 2)); pullRequestStore.save(pullRequest())
            pullRequestStore.save(pullRequest(pullRequestB, repositoryB, 3))
            actionItemStore.save(actionItem(itemForZ, pullRequestZ, repositoryA, versionB).copy(activityAt = t0.minusSeconds(10)))
            actionItemStore.save(actionItem(itemForASecond, pullRequestA, repositoryA, versionB).copy(activityAt = t0.plusSeconds(1)))
            actionItemStore.save(actionItem(itemForAEarlier, pullRequestA, repositoryA, versionA).copy(activityAt = t0.minusSeconds(1)))
            actionItemStore.save(actionItem(actionItemB, pullRequestB, repositoryB, versionB))
            assertEquals(listOf(repositoryA, repositoryB), reminderProjectionStore.listRepositoriesWithActionableItems().map { it.repositoryId })
            assertEquals(listOf(itemForAEarlier, itemForASecond, itemForZ), reminderProjectionStore.listActionableItems(repositoryA).map { it.actionItemId })
        }
    }

    @Test fun `reminder action items are ordered by exact fractional activity chronology`() = runTest {
        val wholeSecondId = ActionItemId("ai_whole_second")
        val fractionalSecondId = ActionItemId("ai_fractional_second")
        persistence.inTransaction {
            configurationStore.save(configuration())
            pullRequestStore.save(pullRequest())
            actionItemStore.save(
                actionItem(wholeSecondId).copy(activityAt = Instant.parse("2026-08-15T08:00:00Z")),
            )
            actionItemStore.save(
                actionItem(fractionalSecondId).copy(activityAt = Instant.parse("2026-08-15T08:00:00.500000000Z")),
            )

            assertEquals(
                listOf(wholeSecondId, fractionalSecondId),
                reminderProjectionStore.listActionableItems(repositoryA).map { it.actionItemId },
            )
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
        yield()
        val returnedBeforeWriterFinished = reader.isCompleted
        val earlyValue = if (returnedBeforeWriterFinished) reader.await() else null
        allowRollback.complete(Unit)
        writer.await()
        assertNull(if (returnedBeforeWriterFinished) earlyValue else reader.await())
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

private fun <T> clearWhenMutable(values: List<T>) {
    val mutable = values as? MutableList<T> ?: return
    try {
        mutable.clear()
    } catch (_: UnsupportedOperationException) {
        // An immutable/unmodifiable List is a valid defensive result.
    }
}
