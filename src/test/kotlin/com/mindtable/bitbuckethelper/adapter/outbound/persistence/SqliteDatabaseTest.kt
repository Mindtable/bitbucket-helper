package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import java.nio.file.Path
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
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

    @Test
    fun `V0002 creates complete triage state and rolls back to V0001`() {
        val database = SqliteDatabase.open(temporaryDirectory.resolve("v2/state.sqlite"))
        database.dataSource.connection.use { connection ->
            Liquibase("db/changelog/db.changelog-master.xml", ClassLoaderResourceAccessor(), JdbcConnection(connection)).use { liquibase ->
                liquibase.update(1, Contexts(), LabelExpression())
                assertEquals(setOf("bitbucket_connection_snapshot"), applicationTables(connection))
                liquibase.update(1, Contexts(), LabelExpression())
                assertEquals(
                    setOf("action_item", "build_observation", "configured_repository", "installation_configuration", "notification_attempt", "notification_intent", "pull_request", "readiness_check", "synchronization_checkpoint", "synchronization_failure", "bitbucket_connection_snapshot"),
                    applicationTables(connection),
                )
                assertTrue(columns(connection, "notification_intent").none { it.startsWith("lease_") })
                liquibase.rollback(1, Contexts(), LabelExpression())
                assertEquals(setOf("bitbucket_connection_snapshot"), applicationTables(connection))
            }
        }
    }

    @Test
    fun `V0003 adds only notification lease state and rolls back to V0002`() {
        val database = SqliteDatabase.open(temporaryDirectory.resolve("v3/state.sqlite"))
        database.dataSource.connection.use { connection ->
            Liquibase("db/changelog/db.changelog-master.xml", ClassLoaderResourceAccessor(), JdbcConnection(connection)).use { liquibase ->
                liquibase.update(2, Contexts(), LabelExpression())
                val before = applicationTables(connection)
                liquibase.update(1, Contexts(), LabelExpression())
                assertEquals(setOf("lease_owner", "lease_acquired_at", "lease_expires_at"), columns(connection, "notification_intent").filter { it.startsWith("lease_") }.toSet())
                assertEquals(before, applicationTables(connection))
                liquibase.rollback(1, Contexts(), LabelExpression())
                assertEquals(before, applicationTables(connection))
                assertTrue(columns(connection, "notification_intent").none { it.startsWith("lease_") })
            }
        }
    }

    private fun applicationTables(connection: java.sql.Connection): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'databasechange%'").use { result ->
            buildSet { while (result.next()) add(result.getString(1)) }
        }
    }

    private fun columns(connection: java.sql.Connection, table: String): List<String> = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { result -> buildList { while (result.next()) add(result.getString("name")) } }
    }
}
