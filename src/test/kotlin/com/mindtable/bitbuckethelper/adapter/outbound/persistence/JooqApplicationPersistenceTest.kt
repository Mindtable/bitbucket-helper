package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import com.mindtable.bitbuckethelper.application.contract.ApplicationPersistenceContract
import com.mindtable.bitbuckethelper.application.contract.*
import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.BackendLogLevel
import com.mindtable.bitbuckethelper.domain.shared.*
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
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

    @Test
    fun `failed transaction records one redacted event before rethrowing the identical failure`() = runTest {
        val path = Files.createTempDirectory("jooq-failure-event").resolve("state.sqlite")
        val events = mutableListOf<BackendLogEvent>()
        val failure = IllegalStateException("SQL=select * from private_table binds=token rows=private-row")

        JooqApplicationPersistence.open(path, BackendEventRecorder(events::add)).use { persistence ->
            val observed = try {
                persistence.inTransaction {
                    throw failure
                }
                null
            } catch (thrown: Throwable) {
                thrown
            }

            assertSame(failure, observed)
            val event = events.single() as BackendLogEvent.PersistenceTransactionFailed
            assertEquals("transaction", event.operation)
            assertSame(failure, event.failure)
            assertEquals(BackendLogLevel.ERROR, event.level)
            assertEquals("persistence.transaction.failed(operation=transaction, failure=<redacted>)", event.toString())
            assertFalse(event.toString().contains("private_table"))
            assertFalse(event.toString().contains("token"))
            assertFalse(event.toString().contains("private-row"))
        }
    }

    @Test
    fun `successful transaction does not record SQL or data events`() = runTest {
        val path = Files.createTempDirectory("jooq-success-event").resolve("state.sqlite")
        val events = mutableListOf<BackendLogEvent>()
        JooqApplicationPersistence.open(path, BackendEventRecorder(events::add)).use { persistence ->
            persistence.inTransaction { configurationStore.find() }
            assertTrue(events.isEmpty())
        }
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

    @Test fun `legacy variable-precision timestamp rows retain exact due semantics`() = runTest {
        val path = Files.createTempDirectory("jooq-legacy-timestamp").resolve("state.sqlite")
        JooqApplicationPersistence.open(path).close()
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().normalize()}").use { connection ->
            connection.prepareStatement(
                "INSERT INTO notification_intent VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            ).use { statement ->
                listOf(
                    "ni_legacy_fractional",
                    "delivery-legacy-fractional",
                    "Title",
                    "Body",
                    null,
                    "DEFAULT",
                    "2026-08-15T08:00:00.500Z",
                    "PENDING",
                    0,
                    "2026-08-15T08:00:00.500Z",
                    null,
                    null,
                    null,
                ).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }

        JooqApplicationPersistence.open(path).use { persistence ->
            persistence.inTransaction {
                assertTrue(
                    notificationIntentStore.findDue(Instant.parse("2026-08-15T08:00:00Z"), 10).isEmpty(),
                )
                assertEquals(
                    Instant.parse("2026-08-15T08:00:00.500Z"),
                    notificationIntentStore.find(NotificationIntentId("ni_legacy_fractional"))!!.nextAttemptAt,
                )
            }
        }
    }
}
