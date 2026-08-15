package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import com.mindtable.bitbuckethelper.application.model.BitbucketAccount
import com.mindtable.bitbuckethelper.application.model.ConnectionFailure
import com.mindtable.bitbuckethelper.application.model.ConnectionFailureCode
import com.mindtable.bitbuckethelper.application.model.ConnectionState
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JooqBitbucketConnectionRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `find is null before the first refresh`() = runTest {
        withRepository { repository, _ ->
            assertNull(repository.find())
        }
    }

    @Test
    fun `success inserts and a later failure preserves last known good account`() = runTest {
        withRepository { repository, dataSource ->
            val firstAttempt = Instant.parse("2026-08-15T10:15:30Z")
            val failedAttempt = Instant.parse("2026-08-15T10:16:30Z")
            val account = BitbucketAccount("{account-uuid}", "Ada Lovelace", "ada")

            repository.recordSuccess(account, firstAttempt)
            val failed = repository.recordFailure(
                ConnectionFailure(ConnectionFailureCode.NETWORK, "Bitbucket is unreachable"),
                failedAttempt,
            )

            assertEquals(ConnectionState.FAILED, failed.state)
            assertEquals(account, failed.account)
            assertEquals(firstAttempt, failed.lastSuccessAt)
            assertEquals(failedAttempt, failed.lastAttemptAt)
            assertEquals(ConnectionFailureCode.NETWORK, failed.failure!!.code)
            assertOnlyExpectedTextColumnsArePersisted(dataSource)
            assertSentinelIsAbsentFromEveryTextColumn(dataSource)
        }
    }

    @Test
    fun `success after a failure replaces the account timestamps and failure fields`() = runTest {
        withRepository { repository, dataSource ->
            val firstSuccessAt = Instant.parse("2026-08-15T10:15:30Z")
            val failedAttemptAt = Instant.parse("2026-08-15T10:16:30Z")
            val replacementSuccessAt = Instant.parse("2026-08-15T10:17:30Z")
            val originalAccount = BitbucketAccount("{original-account-uuid}", "Ada Lovelace", "ada")
            val replacementAccount = BitbucketAccount("{replacement-account-uuid}", "Grace Hopper", "amazing-grace")

            repository.recordSuccess(originalAccount, firstSuccessAt)
            repository.recordFailure(
                ConnectionFailure(ConnectionFailureCode.NETWORK, "Bitbucket is unreachable"),
                failedAttemptAt,
            )
            val successful = repository.recordSuccess(replacementAccount, replacementSuccessAt)

            assertEquals(ConnectionState.HEALTHY, successful.state)
            assertEquals(replacementAccount, successful.account)
            assertEquals(replacementSuccessAt, successful.lastAttemptAt)
            assertEquals(replacementSuccessAt, successful.lastSuccessAt)
            assertNull(successful.failure)
            assertFailureFieldsAreNull(dataSource)
        }
    }

    @Test
    fun `failure before any success stores no account`() = runTest {
        withRepository { repository, _ ->
            val failed = repository.recordFailure(
                ConnectionFailure(ConnectionFailureCode.AUTHENTICATION, "Bitbucket rejected the credentials"),
                Instant.parse("2026-08-15T10:15:30Z"),
            )

            assertNull(failed.account)
            assertNull(failed.lastSuccessAt)
        }
    }

    private suspend fun withRepository(
        block: suspend (JooqBitbucketConnectionRepository, org.sqlite.SQLiteDataSource) -> Unit,
    ) {
        val database = SqliteDatabase.open(temporaryDirectory.resolve("state.sqlite"))
        database.migrate()
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        try {
            block(JooqBitbucketConnectionRepository(database.dataSource, dispatcher), database.dataSource)
        } finally {
            dispatcher.close()
            database.close()
        }
    }

    private fun assertSentinelIsAbsentFromEveryTextColumn(dataSource: org.sqlite.SQLiteDataSource) {
        dataSource.connection.use { connection ->
            textColumns(connection).forEach { column ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT \"$column\" FROM bitbucket_connection_snapshot").use { result ->
                        while (result.next()) {
                            assertFalse(result.getString(1)?.contains(SENTINEL) == true)
                        }
                    }
                }
            }
        }
    }

    private fun assertOnlyExpectedTextColumnsArePersisted(dataSource: org.sqlite.SQLiteDataSource) {
        dataSource.connection.use { connection ->
            assertEquals(EXPECTED_TEXT_COLUMNS, textColumns(connection).toSet())
        }
    }

    private fun assertFailureFieldsAreNull(dataSource: org.sqlite.SQLiteDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT failure_code, failure_message FROM bitbucket_connection_snapshot",
                ).use { result ->
                    assertTrue(result.next())
                    assertNull(result.getString("failure_code"))
                    assertNull(result.getString("failure_message"))
                }
            }
        }
    }

    private fun textColumns(connection: java.sql.Connection): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(bitbucket_connection_snapshot)").use { result ->
                buildList {
                    while (result.next()) {
                        if (result.getString("type").equals("TEXT", ignoreCase = true)) {
                            add(result.getString("name"))
                        }
                    }
                }
            }
        }

    private companion object {
        const val SENTINEL = "sentinel-api-token"

        val EXPECTED_TEXT_COLUMNS = setOf(
            "state",
            "account_uuid",
            "display_name",
            "nickname",
            "last_attempt_at",
            "last_success_at",
            "failure_code",
            "failure_message",
        )
    }
}
