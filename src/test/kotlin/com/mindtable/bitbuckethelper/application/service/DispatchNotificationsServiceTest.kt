package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryKey
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryFailureCategory
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryResult
import com.mindtable.bitbuckethelper.application.model.NotificationDispatchSummary
import com.mindtable.bitbuckethelper.application.model.NotificationIntentState
import com.mindtable.bitbuckethelper.application.model.NotificationLease
import com.mindtable.bitbuckethelper.application.model.NotificationRequest
import com.mindtable.bitbuckethelper.application.model.NotificationSound
import com.mindtable.bitbuckethelper.application.model.StoredNotificationAttempt
import com.mindtable.bitbuckethelper.application.model.StoredNotificationIntent
import com.mindtable.bitbuckethelper.application.policy.NotificationRetryPolicy
import com.mindtable.bitbuckethelper.application.port.inbound.DispatchNotifications
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationAttemptOutcome
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationRetryOutcome
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationSender
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEvent
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEventRecorder
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DispatchNotificationsServiceTest {
    @Test
    fun `durable accepted attempt emits one correlated completion event`() = runBlocking {
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val id = NotificationIntentId("ni_event_accepted")
        val transactions = FakeNotificationTransactionRunner(listOf(intent(id.value, "private-key", now)))
        val events = mutableListOf<OperationalEvent>()

        dispatch(
            transactions = transactions,
            sender = NotificationSender { NotificationDeliveryResult.Accepted },
            now = now,
            recorder = OperationalEventRecorder(events::add),
            timeSource = MonotonicTimeSource.sequence(1_000_000L, 5_000_000L),
        )(listOf(id))

        val event = events.filterIsInstance<OperationalEvent.NotificationAttemptFinished>().single()
        assertEquals(id, event.intentId)
        assertEquals(transactions.attempts(id).single().id, event.attemptId)
        assertEquals(1, event.attemptNumber)
        assertEquals(NotificationAttemptOutcome.ACCEPTED, event.outcome)
        assertEquals(NotificationRetryOutcome.ACCEPTED, event.retryDecision)
        assertEquals(4L, event.durationMilliseconds)
        assertEquals(null, event.failureCategory)
        assertEquals(null, event.ambiguous)
    }

    @Test
    fun `failed attempts expose safe retry decisions after durable completion`() = runBlocking {
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val retryId = NotificationIntentId("ni_event_retry")
        val retryEvents = mutableListOf<OperationalEvent>()
        val retryFailure = NotificationDeliveryResult.Failed(
            category = NotificationDeliveryFailureCategory.DELIVERY_TIMEOUT,
            ambiguous = true,
        )
        dispatch(
            transactions = FakeNotificationTransactionRunner(listOf(intent(retryId.value, "retry-private-key", now))),
            sender = NotificationSender { retryFailure },
            now = now,
            recorder = OperationalEventRecorder(retryEvents::add),
            timeSource = MonotonicTimeSource { 0L },
        )(listOf(retryId))

        val retryEvent = retryEvents.filterIsInstance<OperationalEvent.NotificationAttemptFinished>().single()
        assertEquals(retryId, retryEvent.intentId)
        assertEquals(NotificationAttemptOutcome.FAILED, retryEvent.outcome)
        assertEquals(NotificationDeliveryFailureCategory.DELIVERY_TIMEOUT, retryEvent.failureCategory)
        assertEquals(true, retryEvent.ambiguous)
        assertEquals(NotificationRetryOutcome.RETRY_SCHEDULED, retryEvent.retryDecision)

        val exhaustedId = NotificationIntentId("ni_event_exhausted")
        val exhaustedEvents = mutableListOf<OperationalEvent>()
        dispatch(
            transactions = FakeNotificationTransactionRunner(
                listOf(intent(exhaustedId.value, "exhausted-private-key", now, attemptCount = 6)),
            ),
            sender = NotificationSender { retryFailure },
            now = now,
            recorder = OperationalEventRecorder(exhaustedEvents::add),
            timeSource = MonotonicTimeSource { 0L },
        )(listOf(exhaustedId))

        assertEquals(
            NotificationRetryOutcome.EXHAUSTED,
            exhaustedEvents.filterIsInstance<OperationalEvent.NotificationAttemptFinished>().single().retryDecision,
        )
    }

    @Test
    fun `retry scan sends due intents in created time then identifier order`() = runBlocking {
        // Catches a retry scan that exposes storage order instead of the durable dispatch order.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val transactions = FakeNotificationTransactionRunner(
            listOf(
                intent("ni_z", "key-z", now.minusSeconds(60)),
                intent("ni_b", "key-b", now.minusSeconds(120)),
                intent("ni_a", "key-a", now.minusSeconds(120)),
            ),
        )
        val sentKeys = mutableListOf<String>()
        val dispatch = DispatchNotificationsService(
            transactions = transactions,
            sender = NotificationSender { request ->
                sentKeys += request.deliveryKey.value
                NotificationDeliveryResult.Accepted
            },
            retryPolicy = NotificationRetryPolicy(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val retry = RetryPendingNotificationsService(transactions, dispatch, Clock.fixed(now, ZoneOffset.UTC))

        val summary = retry()

        assertEquals(listOf("key-a", "key-b", "key-z"), sentKeys)
        assertEquals(listOf("ni_a", "ni_b", "ni_z"), summary.attemptedIntentIds.map { it.value })
        assertEquals(3, summary.acceptedCount)
    }

    @Test
    fun `retry scan requests one bounded batch and leaves overflow due`() = runBlocking {
        // Catches an unbounded due query or forwarding more intents than the requested store limit.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val due = (0..100).map { index ->
            val suffix = index.toString().padStart(3, '0')
            intent("ni_batch_$suffix", "key-batch-$suffix", now.minusSeconds(60))
        }
        val transactions = FakeNotificationTransactionRunner(due)
        val dispatch = dispatch(
            transactions,
            NotificationSender { NotificationDeliveryResult.Accepted },
            now,
        )

        val summary = RetryPendingNotificationsService(
            transactions,
            dispatch,
            Clock.fixed(now, ZoneOffset.UTC),
        )()

        assertEquals(
            listOf(FakeNotificationTransactionRunner.FindDueInvocation(now, limit = 100)),
            transactions.findDueInvocations(),
        )
        assertEquals(100, summary.attemptedIntentIds.size)
        assertEquals(100, summary.acceptedCount)
        assertEquals(NotificationIntentState.ACCEPTED, transactions.intent(NotificationIntentId("ni_batch_099"))?.state)
        assertEquals(NotificationIntentState.PENDING, transactions.intent(NotificationIntentId("ni_batch_100"))?.state)
        assertEquals(emptyList<StoredNotificationAttempt>(), transactions.attempts(NotificationIntentId("ni_batch_100")))
    }

    @Test
    fun `focused dispatch orders reversed identifiers by persisted creation time then identifier`() = runBlocking {
        // Catches deleting service-level sorting while the due-scan fake remains independently sorted.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val lateId = NotificationIntentId("ni_late")
        val sameTimeB = NotificationIntentId("ni_same_b")
        val sameTimeA = NotificationIntentId("ni_same_a")
        val transactions = FakeNotificationTransactionRunner(
            listOf(
                intent(lateId.value, "key-late", now.minusSeconds(60)),
                intent(sameTimeB.value, "key-same-b", now.minusSeconds(120)),
                intent(sameTimeA.value, "key-same-a", now.minusSeconds(120)),
            ),
        )
        val sentKeys = mutableListOf<String>()

        dispatch(
            transactions,
            NotificationSender { request ->
                sentKeys += request.deliveryKey.value
                NotificationDeliveryResult.Accepted
            },
            now,
        )(listOf(lateId, sameTimeB, sameTimeA))

        assertEquals(listOf("key-same-a", "key-same-b", "key-late"), sentKeys)
    }

    @Test
    fun `each claim uses a unique worker and a two minute lease`() = runBlocking {
        // Catches owner-token reuse across claims or a lease duration that cannot bound delivery ownership.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val firstId = NotificationIntentId("ni_first")
        val secondId = NotificationIntentId("ni_second")
        val transactions = FakeNotificationTransactionRunner(
            listOf(
                intent(firstId.value, "key-first", now),
                intent(secondId.value, "key-second", now),
            ),
        )
        val sender = NotificationSender { NotificationDeliveryResult.Accepted }
        val dispatcher = dispatch(transactions, sender, now)

        dispatcher(listOf(firstId))
        dispatcher(listOf(secondId))

        val claims = transactions.claimInvocations()
        assertEquals(2, claims.map { it.owner }.distinct().size)
        claims.forEach { claim ->
            assertEquals(now, claim.acquiredAt)
            assertEquals(Duration.ofMinutes(2), Duration.between(claim.acquiredAt, claim.expiresAt))
        }
    }

    @Test
    fun `simultaneous workers share the active lease so only one sends`() = runBlocking {
        // Catches a non-atomic lease claim that lets overlapping workers both invoke the sender.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val id = NotificationIntentId("ni_race")
        val transactions = FakeNotificationTransactionRunner(listOf(intent(id.value, "key-race", now)))
        val senderEntered = CompletableDeferred<Unit>()
        val releaseSender = CompletableDeferred<Unit>()
        val sends = AtomicInteger()
        val sender = NotificationSender {
            sends.incrementAndGet()
            senderEntered.complete(Unit)
            releaseSender.await()
            NotificationDeliveryResult.Accepted
        }
        val start = CompletableDeferred<Unit>()

        val first = async {
            start.await()
            dispatch(transactions, sender, now)(listOf(id))
        }
        val second = async {
            start.await()
            dispatch(transactions, sender, now)(listOf(id))
        }
        start.complete(Unit)
        withTimeout(5_000) { senderEntered.await() }
        withTimeout(5_000) {
            while (transactions.claimInvocations().size < 2) kotlinx.coroutines.yield()
        }
        releaseSender.complete(Unit)

        val summaries = listOf(first.await(), second.await())
        assertEquals(1, sends.get())
        assertEquals(1, summaries.sumOf { it.attemptedIntentIds.size })
        assertEquals(1, transactions.attempts(id).size)
    }

    @Test
    fun `expired lease is reclaimable`() = runBlocking {
        // Catches a claim path that treats an expired owner as permanently active.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val id = NotificationIntentId("ni_stale")
        val transactions = FakeNotificationTransactionRunner(
            listOf(
                intent(
                    id = id.value,
                    deliveryKey = "key-stale",
                    createdAt = now.minusSeconds(300),
                    lease = NotificationLease(
                        owner = "gone-worker",
                        acquiredAt = now.minusSeconds(180),
                        expiresAt = now.minusSeconds(60),
                    ),
                ),
            ),
        )
        val sends = AtomicInteger()

        val summary = dispatch(
            transactions,
            NotificationSender {
                sends.incrementAndGet()
                NotificationDeliveryResult.Accepted
            },
            now,
        )(listOf(id))

        assertEquals(1, sends.get())
        assertEquals(listOf(id), summary.attemptedIntentIds)
        assertEquals(NotificationIntentState.ACCEPTED, transactions.intent(id)?.state)
    }

    @Test
    fun `sender runs outside transactions and attempt completion uses its own short transaction`() = runBlocking {
        // Catches a process invocation inside the database transaction or attempt recording mixed into the claim transaction.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val id = NotificationIntentId("ni_transaction_boundary")
        val transactions = FakeNotificationTransactionRunner(
            listOf(intent(id.value, "key-transaction", now)),
        )

        dispatch(
            transactions,
            NotificationSender {
                assertFalse(transactions.isTransactionActive)
                NotificationDeliveryResult.Accepted
            },
            now,
        )(listOf(id))

        assertEquals(
            listOf("completeAttempt:${id.value}"),
            transactions.transactions().single { operations ->
                operations.any { it == "completeAttempt:${id.value}" }
            },
        )
    }

    @Test
    fun `accepted and not yet due intents are not resent`() = runBlocking {
        // Catches a focused dispatch that bypasses terminal state or next-attempt eligibility.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val acceptedId = NotificationIntentId("ni_accepted")
        val futureId = NotificationIntentId("ni_future")
        val transactions = FakeNotificationTransactionRunner(
            listOf(
                intent(
                    acceptedId.value,
                    "key-accepted",
                    now.minusSeconds(60),
                    state = NotificationIntentState.ACCEPTED,
                ),
                intent(
                    futureId.value,
                    "key-future",
                    now.minusSeconds(60),
                    nextAttemptAt = now.plusSeconds(60),
                ),
            ),
        )
        val sends = AtomicInteger()

        val summary = dispatch(
            transactions,
            NotificationSender {
                sends.incrementAndGet()
                NotificationDeliveryResult.Accepted
            },
            now,
        )(listOf(acceptedId, futureId))

        assertEquals(0, sends.get())
        assertEquals(emptyList<NotificationIntentId>(), summary.attemptedIntentIds)
        assertEquals(0, transactions.attempts(acceptedId).size)
        assertEquals(0, transactions.attempts(futureId).size)
    }

    @Test
    fun `retry reuses the identical persisted request and delivery key`() = runBlocking {
        // Catches rebuilding a retry payload or replacing its durable provider identity.
        val firstAttemptAt = Instant.parse("2026-08-16T09:00:00Z")
        val retryAt = firstAttemptAt.plusSeconds(60)
        val id = NotificationIntentId("ni_retry")
        val original = intent(id.value, "stable-delivery-key", firstAttemptAt.minusSeconds(60))
        val transactions = FakeNotificationTransactionRunner(listOf(original))
        val sent = mutableListOf<NotificationRequest>()
        val sender = NotificationSender { request ->
            sent += request
            if (sent.size == 1) {
                NotificationDeliveryResult.Failed(
                    NotificationDeliveryFailureCategory.DELIVERY_FAILED,
                    ambiguous = false,
                )
            } else {
                NotificationDeliveryResult.Accepted
            }
        }

        val first = dispatch(transactions, sender, firstAttemptAt)(listOf(id))
        val second = dispatch(transactions, sender, retryAt)(listOf(id))

        assertEquals(1, first.retryScheduledCount)
        assertEquals(1, second.acceptedCount)
        assertEquals(listOf(original.request, original.request), sent)
        assertSame(sent[0], sent[1])
        assertEquals("stable-delivery-key", sent[1].deliveryKey.value)
    }

    @Test
    fun `terminal sender failure records one exhausted attempt`() = runBlocking {
        // Catches dropping a failed result or mapping the policy's exhausted decision to pending state.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val id = NotificationIntentId("ni_exhausted")
        val transactions = FakeNotificationTransactionRunner(listOf(intent(id.value, "key-exhausted", now)))
        val rejected = NotificationDeliveryResult.Failed(
            NotificationDeliveryFailureCategory.INVALID_ARGUMENTS,
            ambiguous = false,
        )

        val summary = dispatch(transactions, NotificationSender { rejected }, now)(listOf(id))

        assertEquals(1, summary.exhaustedCount)
        assertEquals(0, summary.acceptedCount)
        assertEquals(0, summary.retryScheduledCount)
        assertEquals(rejected, transactions.attempts(id).single().result)
        assertEquals(NotificationIntentState.EXHAUSTED, transactions.intent(id)?.state)
        assertEquals(null, transactions.intent(id)?.nextAttemptAt)
    }

    @Test
    fun `post commit dispatcher forwards only the committed intent identifiers`() = runBlocking {
        // Catches a post-commit trigger that scans unrelated pending work or changes the committed ID set.
        val committed = listOf(NotificationIntentId("ni_committed_b"), NotificationIntentId("ni_committed_a"))
        var received: List<NotificationIntentId>? = null
        val focusedDispatch = DispatchNotifications { ids ->
            received = ids
            NotificationDispatchSummary(ids, acceptedCount = 0, retryScheduledCount = 0, exhaustedCount = 0)
        }

        ImmediatePostCommitNotificationDispatcher(focusedDispatch).dispatchCommitted(committed)

        assertEquals(committed, received)
    }

    @Test
    fun `sender failure after commit cannot roll back the creating transaction`() = runBlocking {
        // Catches invoking the external sender before the durable intent creation transaction commits.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val created = intent("ni_committed_before_send", "key-committed", now)
        val transactions = FakeNotificationTransactionRunner()
        transactions.inTransaction {
            notificationIntentStore.insertIfAbsent(created)
        }
        val dispatcher = ImmediatePostCommitNotificationDispatcher(
            dispatch(
                transactions,
                NotificationSender {
                    assertFalse(transactions.isTransactionActive)
                    assertEquals(created.request, transactions.intent(created.id)?.request)
                    throw IllegalStateException("provider unavailable")
                },
                now,
            ),
        )

        val failure = runCatching {
            dispatcher.dispatchCommitted(listOf(created.id))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(created.request, transactions.intent(created.id)?.request)
        assertEquals(created.createdAt, transactions.intent(created.id)?.createdAt)
    }

    @Test
    fun `cancellation before process start releases the lease without recording an attempt`() = runBlocking {
        // Catches treating a definitely-not-started cancellation as an ambiguous delivery attempt.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val id = NotificationIntentId("ni_cancel_before_start")
        val transactions = FakeNotificationTransactionRunner(
            listOf(intent(id.value, "key-cancel-before", now)),
        )

        val failure = runCatching {
            dispatch(
                transactions,
                NotificationSender { throw CancellationException("cancelled before process start") },
                now,
            )(listOf(id))
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(null, transactions.intent(id)?.lease)
        assertEquals(emptyList<StoredNotificationAttempt>(), transactions.attempts(id))
    }

    @Test
    fun `cancellation after process start records ambiguous retry before rethrowing`() = runBlocking {
        // Catches cancellation skipping the durable ambiguous attempt returned after the provider process started.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val id = NotificationIntentId("ni_cancel_after_start")
        val transactions = FakeNotificationTransactionRunner(
            listOf(intent(id.value, "key-cancel-after", now)),
        )
        val observedCancellation = CompletableDeferred<CancellationException>()
        val ambiguous = NotificationDeliveryResult.Failed(
            NotificationDeliveryFailureCategory.AMBIGUOUS_PROCESS_FAILURE,
            ambiguous = true,
        )

        val job = launch {
            try {
                dispatch(
                    transactions,
                    NotificationSender {
                        coroutineContext.cancel(CancellationException("cancelled after process start"))
                        ambiguous
                    },
                    now,
                )(listOf(id))
            } catch (cancellation: CancellationException) {
                observedCancellation.complete(cancellation)
            }
        }

        withTimeout(5_000) { observedCancellation.await() }
        job.join()
        val attempt = transactions.attempts(id).single()
        assertEquals(ambiguous, attempt.result)
        assertEquals(1, attempt.attemptNumber)
        assertEquals(now, attempt.completedAt)
        assertEquals(NotificationIntentState.PENDING, transactions.intent(id)?.state)
        assertEquals(now.plusSeconds(60), transactions.intent(id)?.nextAttemptAt)
        assertEquals(null, transactions.intent(id)?.lease)
    }

    @Test
    fun `cancellation during attempt completion records the returned delivery before rethrowing`() = runBlocking {
        // Catches sampling cancellation only before a cancellable completion transaction.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val id = NotificationIntentId("ni_cancel_during_completion")
        val transactions = FakeNotificationTransactionRunner(
            listOf(intent(id.value, "key-cancel-during-completion", now)),
        )
        val completionEntered = CompletableDeferred<Unit>()
        val releaseCompletion = CompletableDeferred<Unit>()
        transactions.blockNextCompletion(completionEntered, releaseCompletion)
        val observedFailure = CompletableDeferred<Throwable>()
        val originalCancellation = CancellationException("cancelled during attempt completion")

        val job = launch {
            try {
                dispatch(
                    transactions,
                    NotificationSender { NotificationDeliveryResult.Accepted },
                    now,
                )(listOf(id))
            } catch (failure: Throwable) {
                observedFailure.complete(failure)
            }
        }

        withTimeout(5_000) { completionEntered.await() }
        job.cancel(originalCancellation)
        kotlinx.coroutines.yield()
        releaseCompletion.complete(Unit)
        val failure = withTimeout(5_000) { observedFailure.await() }
        job.join()

        assertTrue(failure === originalCancellation || failure.cause === originalCancellation)
        val attempt = transactions.attempts(id).single()
        assertEquals(NotificationDeliveryResult.Accepted, attempt.result)
        assertEquals(NotificationIntentState.ACCEPTED, transactions.intent(id)?.state)
        assertEquals(null, transactions.intent(id)?.lease)
    }

    @Test
    fun `failed post-start cleanup is safely observable and its lease remains recoverable`() = runBlocking {
        // Catches a cleanup failure replacing cancellation, leaking diagnostics, or losing bounded recovery state.
        val now = Instant.parse("2026-08-16T09:00:00Z")
        val id = NotificationIntentId("ni_cleanup_failure")
        val transactions = FakeNotificationTransactionRunner(
            listOf(intent(id.value, "private-delivery-key", now)),
        )
        transactions.failNextCompletion(IllegalStateException("PRIVATE-PERSISTENCE-DIAGNOSTIC"))
        val observedFailure = CompletableDeferred<Throwable>()
        val ambiguous = NotificationDeliveryResult.Failed(
            NotificationDeliveryFailureCategory.AMBIGUOUS_PROCESS_FAILURE,
            ambiguous = true,
        )

        val job = launch {
            try {
                dispatch(
                    transactions,
                    NotificationSender {
                        coroutineContext.cancel(CancellationException("cancelled after process start"))
                        ambiguous
                    },
                    now,
                )(listOf(id))
            } catch (failure: Throwable) {
                observedFailure.complete(failure)
            }
        }

        val failure = withTimeout(5_000) { observedFailure.await() }
        job.join()
        assertTrue(failure is CancellationException)
        assertEquals(listOf("Notification attempt cleanup failed"), failure.suppressed.map { it.message })
        assertFalse(failure.toString().contains("PRIVATE-PERSISTENCE-DIAGNOSTIC"))
        assertEquals(emptyList<StoredNotificationAttempt>(), transactions.attempts(id))
        val retainedLease = transactions.intent(id)?.lease
        assertEquals(now.plusSeconds(120), retainedLease?.expiresAt)

        val recovery = dispatch(
            transactions,
            NotificationSender { NotificationDeliveryResult.Accepted },
            now.plusSeconds(120),
        )(listOf(id))
        assertEquals(listOf(id), recovery.attemptedIntentIds)
        assertEquals(NotificationIntentState.ACCEPTED, transactions.intent(id)?.state)
    }

    private fun intent(
        id: String,
        deliveryKey: String,
        createdAt: Instant,
        nextAttemptAt: Instant? = createdAt,
        state: NotificationIntentState = NotificationIntentState.PENDING,
        lease: NotificationLease? = null,
        attemptCount: Int = 0,
    ): StoredNotificationIntent = StoredNotificationIntent(
        id = NotificationIntentId(id),
        request = NotificationRequest(
            deliveryKey = NotificationDeliveryKey(deliveryKey),
            title = "Title $deliveryKey",
            body = "Body $deliveryKey",
            openUrl = null,
            sound = NotificationSound.DEFAULT,
        ),
        createdAt = createdAt,
        state = state,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        lease = lease,
    )

    private fun dispatch(
        transactions: FakeNotificationTransactionRunner,
        sender: NotificationSender,
        now: Instant,
        recorder: OperationalEventRecorder = OperationalEventRecorder.NONE,
        timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
    ): DispatchNotificationsService = DispatchNotificationsService(
        transactions = transactions,
        sender = sender,
        retryPolicy = NotificationRetryPolicy(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        operationalEventRecorder = recorder,
        timeSource = timeSource,
    )
}

private fun MonotonicTimeSource.Companion.sequence(vararg values: Long): MonotonicTimeSource {
    val iterator = values.iterator()
    return MonotonicTimeSource { check(iterator.hasNext()) { "time source exhausted" }; iterator.nextLong() }
}
