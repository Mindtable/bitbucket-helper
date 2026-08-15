package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteDatabaseTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `migration creates the singleton table and records V0001 idempotently`() {
        val database = SqliteDatabase.open(temporaryDirectory.resolve("nested/state.sqlite"))

        database.migrate()
        database.migrate()

        database.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='bitbucket_connection_snapshot'",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
                statement.executeQuery(
                    "SELECT COUNT(*) FROM databasechangelog WHERE id='V0001-create-bitbucket-connection-snapshot'",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `connections enforce foreign keys and use a bounded busy timeout`() {
        val database = SqliteDatabase.open(temporaryDirectory.resolve("nested/state.sqlite"))

        database.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA foreign_keys").use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
                statement.executeQuery("PRAGMA busy_timeout").use { result ->
                    assertTrue(result.next())
                    assertTrue(result.getInt(1) in 1..30_000)
                }
            }
        }
    }
}
