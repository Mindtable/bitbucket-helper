package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import com.mindtable.bitbuckethelper.application.contract.ApplicationPersistenceContract
import com.mindtable.bitbuckethelper.application.contract.*
import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.domain.shared.*
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JooqApplicationPersistenceTest : ApplicationPersistenceContract() {
    override fun createPersistence(): ApplicationTransactionRunner {
        val path = Files.createTempDirectory("jooq-application-persistence").resolve("state.sqlite")
        return JooqApplicationPersistence.open(path)
    }

    @Test fun `separate adapters acknowledge an exact version only once`() = runTest {
        val path = Files.createTempDirectory("jooq-ack-cas").resolve("state.sqlite")
        JooqApplicationPersistence.open(path).use { first -> JooqApplicationPersistence.open(path).use { second ->
            first.inTransaction { actionItemStore.save(actionItem()) }
            val results = listOf(first, second).map { adapter -> async {
                adapter.inTransaction { actionItemStore.acknowledge(actionItemA, versionA, t0.plusSeconds(10)) }
            } }.awaitAll()
            assertEquals(1, results.count { it is StoredAcknowledgmentResult.Updated })
            assertEquals(1, results.count { it is StoredAcknowledgmentResult.AlreadyApplied })
        } }
    }

    @Test fun `separate adapters complete one leased attempt only once`() = runTest {
        val path = Files.createTempDirectory("jooq-completion-cas").resolve("state.sqlite")
        JooqApplicationPersistence.open(path).use { first -> JooqApplicationPersistence.open(path).use { second ->
            first.inTransaction { notificationIntentStore.insertIfAbsent(intent()); notificationIntentStore.tryClaim(intent().id, "owner", t0, t0.plusSeconds(30)) }
            val completion = NotificationAttemptCompletion(attempt(), NotificationIntentState.ACCEPTED, null)
            val results = listOf(first, second).map { adapter -> async { adapter.inTransaction { notificationIntentStore.completeAttempt(intent().id, "owner", completion) } } }.awaitAll()
            assertEquals(listOf(false, true), results.sorted())
            first.inTransaction {
                assertEquals(listOf(completion.attempt), notificationIntentStore.listAttempts(intent().id))
                assertEquals(1, notificationIntentStore.find(intent().id)!!.attemptCount)
                assertNull(notificationIntentStore.find(intent().id)!!.lease)
            }
        } }
    }

    @Test fun `delivery key does not replace notification intent identity`() = runTest {
        val path = Files.createTempDirectory("jooq-delivery-key").resolve("state.sqlite")
        JooqApplicationPersistence.open(path).use { persistence ->
            val first = intent(NotificationIntentId("ni_delivery_a"))
            val second = intent(NotificationIntentId("ni_delivery_b")).copy(request = intent(NotificationIntentId("ni_delivery_a")).request)
            persistence.inTransaction {
                assertTrue(notificationIntentStore.insertIfAbsent(first) is NotificationIntentInsertResult.Inserted)
                assertTrue(notificationIntentStore.insertIfAbsent(second) is NotificationIntentInsertResult.Inserted)
                assertEquals(second, notificationIntentStore.find(second.id))
            }
        }
    }
}
