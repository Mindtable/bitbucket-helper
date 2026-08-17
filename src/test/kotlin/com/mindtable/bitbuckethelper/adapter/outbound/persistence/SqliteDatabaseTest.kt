package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SqliteDatabaseTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `open creates missing database and directories with owner only permissions before first connection`() {
        val databasePath = temporaryDirectory.resolve("private/nested/state.sqlite")

        SqliteDatabase.open(databasePath).use {
            assertTrue(Files.isRegularFile(databasePath, LinkOption.NOFOLLOW_LINKS))
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(databasePath, LinkOption.NOFOLLOW_LINKS),
            )
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(databasePath.parent, LinkOption.NOFOLLOW_LINKS),
            )
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(databasePath.parent.parent, LinkOption.NOFOLLOW_LINKS),
            )
        }
    }

    @Test
    fun `open rejects shared existing database without deleting or changing it`() {
        val databasePath = temporaryDirectory.resolve("shared.sqlite")
        Files.writeString(databasePath, "preserve-me")
        Files.setPosixFilePermissions(databasePath, PosixFilePermissions.fromString("rw-r--r--"))

        assertThrows(IllegalArgumentException::class.java) {
            SqliteDatabase.open(databasePath)
        }

        assertEquals("preserve-me", Files.readString(databasePath))
        assertEquals(
            PosixFilePermissions.fromString("rw-r--r--"),
            Files.getPosixFilePermissions(databasePath, LinkOption.NOFOLLOW_LINKS),
        )
        assertFalse(Files.exists(databasePath.resolveSibling("shared.sqlite-journal")))
        assertFalse(Files.exists(databasePath.resolveSibling("shared.sqlite-wal")))
        assertFalse(Files.exists(databasePath.resolveSibling("shared.sqlite-shm")))
    }

    @Test
    fun `open rejects symlink database without changing its target`() {
        val target = temporaryDirectory.resolve("target.sqlite")
        Files.writeString(target, "preserve-target")
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
        val databasePath = temporaryDirectory.resolve("linked.sqlite")
        Files.createSymbolicLink(databasePath, target.fileName)

        assertThrows(IllegalArgumentException::class.java) {
            SqliteDatabase.open(databasePath)
        }

        assertEquals("preserve-target", Files.readString(target))
        assertTrue(Files.isSymbolicLink(databasePath))
    }

    @Test
    fun `open rejects missing database below shared ancestor without creating descendants`() {
        val sharedParent = Files.createDirectory(temporaryDirectory.resolve("shared-parent"))
        Files.setPosixFilePermissions(sharedParent, PosixFilePermissions.fromString("rwxr-xr-x"))
        val databasePath = sharedParent.resolve("nested/state.sqlite")

        assertThrows(IllegalArgumentException::class.java) {
            SqliteDatabase.open(databasePath)
        }

        assertFalse(Files.exists(sharedParent.resolve("nested"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `sqlite journal wal and shared memory files remain owner only`() {
        val databasePath = temporaryDirectory.resolve("sidecars/state.sqlite")
        val database = SqliteDatabase.open(databasePath)

        database.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE sidecar_probe (value TEXT NOT NULL)")
            }
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO sidecar_probe VALUES ('journal')")
            }
            assertOwnerOnly(databasePath.resolveSibling("state.sqlite-journal"))
            connection.rollback()
        }

        database.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA journal_mode=WAL").use { result ->
                    assertTrue(result.next())
                    assertEquals("wal", result.getString(1).lowercase())
                }
                statement.execute("INSERT INTO sidecar_probe VALUES ('wal')")
            }
            assertOwnerOnly(databasePath.resolveSibling("state.sqlite-wal"))
            assertOwnerOnly(databasePath.resolveSibling("state.sqlite-shm"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["-journal", "-wal", "-shm"])
    fun `open rejects shared existing sqlite sidecar without changing database or sidecar`(suffix: String) {
        val databasePath = temporaryDirectory.resolve("existing-sidecar.sqlite")
        Files.writeString(databasePath, "preserve-database")
        Files.setPosixFilePermissions(databasePath, PosixFilePermissions.fromString("rw-------"))
        val sidecarPath = databasePath.resolveSibling(databasePath.fileName.toString() + suffix)
        Files.writeString(sidecarPath, "preserve-sidecar")
        Files.setPosixFilePermissions(sidecarPath, PosixFilePermissions.fromString("rw-r--r--"))

        assertThrows(IllegalArgumentException::class.java) {
            SqliteDatabase.open(databasePath)
        }

        assertEquals("preserve-database", Files.readString(databasePath))
        assertEquals("preserve-sidecar", Files.readString(sidecarPath))
        assertEquals(
            PosixFilePermissions.fromString("rw-r--r--"),
            Files.getPosixFilePermissions(sidecarPath, LinkOption.NOFOLLOW_LINKS),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["-journal", "-wal", "-shm"])
    fun `open rejects symlinked sqlite sidecar without changing database or target`(suffix: String) {
        val databasePath = temporaryDirectory.resolve("linked-sidecar.sqlite")
        Files.writeString(databasePath, "preserve-database")
        Files.setPosixFilePermissions(databasePath, PosixFilePermissions.fromString("rw-------"))
        val target = temporaryDirectory.resolve("sidecar-target${suffix}")
        Files.writeString(target, "preserve-target")
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
        val sidecarPath = databasePath.resolveSibling(databasePath.fileName.toString() + suffix)
        Files.createSymbolicLink(sidecarPath, target.fileName)

        assertThrows(IllegalArgumentException::class.java) {
            SqliteDatabase.open(databasePath)
        }

        assertEquals("preserve-database", Files.readString(databasePath))
        assertEquals("preserve-target", Files.readString(target))
        assertTrue(Files.isSymbolicLink(sidecarPath))
    }

    @ParameterizedTest
    @ValueSource(strings = ["-journal", "-wal", "-shm"])
    fun `open rejects orphaned unsafe sqlite sidecar without creating database`(suffix: String) {
        val databasePath = temporaryDirectory.resolve("missing-with-sidecar.sqlite")
        val sidecarPath = databasePath.resolveSibling(databasePath.fileName.toString() + suffix)
        Files.writeString(sidecarPath, "preserve-orphan")
        Files.setPosixFilePermissions(sidecarPath, PosixFilePermissions.fromString("rw-r--r--"))

        assertThrows(IllegalArgumentException::class.java) {
            SqliteDatabase.open(databasePath)
        }

        assertFalse(Files.exists(databasePath))
        assertEquals("preserve-orphan", Files.readString(sidecarPath))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `open rejects replaceable database parent below non sticky shared ancestor without changing database`(
        existingDatabase: Boolean,
    ) {
        val replaceableAncestor = Files.createDirectory(temporaryDirectory.resolve("replaceable-$existingDatabase"))
        Files.setAttribute(replaceableAncestor, "unix:mode", NON_STICKY_SHARED_DIRECTORY_MODE)
        val managedParent = Files.createDirectory(replaceableAncestor.resolve("managed"))
        Files.setPosixFilePermissions(managedParent, PosixFilePermissions.fromString("rwx------"))
        val databasePath = managedParent.resolve("state.sqlite")
        if (existingDatabase) {
            Files.writeString(databasePath, "preserve-database")
            Files.setPosixFilePermissions(databasePath, PosixFilePermissions.fromString("rw-------"))
        }

        assertThrows(IllegalArgumentException::class.java) {
            SqliteDatabase.open(databasePath)
        }

        if (existingDatabase) {
            assertEquals("preserve-database", Files.readString(databasePath))
        } else {
            assertFalse(Files.exists(databasePath))
        }
        assertEquals(
            NON_STICKY_SHARED_DIRECTORY_MODE,
            (Files.getAttribute(replaceableAncestor, "unix:mode") as Int) and UNIX_MODE_MASK,
        )
    }

    @Test
    fun `open accepts owner only database parent below sticky shared ancestor`() {
        val stickyAncestor = Files.createDirectory(temporaryDirectory.resolve("sticky-ancestor"))
        Files.setAttribute(stickyAncestor, "unix:mode", STICKY_SHARED_DIRECTORY_MODE)
        val managedParent = Files.createDirectory(stickyAncestor.resolve("managed"))
        Files.setPosixFilePermissions(managedParent, PosixFilePermissions.fromString("rwx------"))
        val databasePath = managedParent.resolve("state.sqlite")

        SqliteDatabase.open(databasePath).use {
            assertTrue(Files.isRegularFile(databasePath, LinkOption.NOFOLLOW_LINKS))
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(databasePath, LinkOption.NOFOLLOW_LINKS),
            )
        }
        assertEquals(
            STICKY_SHARED_DIRECTORY_MODE,
            (Files.getAttribute(stickyAncestor, "unix:mode") as Int) and UNIX_MODE_MASK,
        )
    }

    @Test
    fun `replacement safe ancestor policy requires trusted owners and sticky child protection`() {
        val cases = listOf(
            ReplacementPolicyCase("other", 0x1ED, "person", false, "untrusted owner 0755"),
            ReplacementPolicyCase("other", 0x16D, "person", false, "untrusted owner 0555"),
            ReplacementPolicyCase("other", 0x3FF, "person", false, "untrusted owner sticky 1777"),
            ReplacementPolicyCase("root", 0x1ED, "root", true, "root owned 0755"),
            ReplacementPolicyCase("person", 0x1ED, "root", true, "current-user owned 0755"),
            ReplacementPolicyCase("root", 0x3FF, "person", true, "root sticky with current-user child"),
            ReplacementPolicyCase("root", 0x3FF, "other", false, "root sticky with untrusted child"),
            ReplacementPolicyCase("root", 0x1FF, "person", false, "root non-sticky 0777"),
        )

        cases.forEach { case ->
            assertEquals(
                case.expected,
                isReplacementSafeAncestor(
                    ownerName = case.owner,
                    currentUserName = "person",
                    unixMode = case.mode,
                    directChildOwnerName = case.childOwner,
                ),
                case.description,
            )
        }
    }

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
                val v1 = schemaSnapshot(connection)
                assertEquals(setOf("bitbucket_connection_snapshot"), v1.keys)
                liquibase.update(1, Contexts(), LabelExpression())
                val v2 = schemaSnapshot(connection)
                assertV2Manifest(v2)
                assertTrue(columns(connection, "notification_intent").none { it.startsWith("lease_") })
                liquibase.rollback(1, Contexts(), LabelExpression())
                assertEquals(v1, schemaSnapshot(connection))
            }
        }
    }

    @Test
    fun `V0003 adds only notification lease state and rolls back to V0002`() {
        val database = SqliteDatabase.open(temporaryDirectory.resolve("v3/state.sqlite"))
        database.dataSource.connection.use { connection ->
            Liquibase("db/changelog/db.changelog-master.xml", ClassLoaderResourceAccessor(), JdbcConnection(connection)).use { liquibase ->
                liquibase.update(2, Contexts(), LabelExpression())
                val before = schemaSnapshot(connection)
                liquibase.update(1, Contexts(), LabelExpression())
                assertEquals(setOf("lease_owner", "lease_acquired_at", "lease_expires_at"), columns(connection, "notification_intent").filter { it.startsWith("lease_") }.toSet())
                val v3 = schemaSnapshot(connection)
                assertEquals(before.keys, v3.keys)
                assertEquals(before.filterKeys { it != "notification_intent" }, v3.filterKeys { it != "notification_intent" })
                val beforeIntent=before.getValue("notification_intent"); val afterIntent=v3.getValue("notification_intent")
                assertEquals(beforeIntent.columns + listOf(Column("lease_owner","TEXT",false,0),Column("lease_acquired_at","TEXT",false,0),Column("lease_expires_at","TEXT",false,0)), afterIntent.columns)
                assertEquals(beforeIntent.indexes + setOf("notification_intent_claim_idx:0:state,lease_expires_at,id", "notification_intent_due_claim_idx:0:state,next_attempt_at,lease_expires_at,created_at,id"), afterIntent.indexes)
                assertEquals(beforeIntent.foreignKeys,afterIntent.foreignKeys)
                liquibase.rollback(1, Contexts(), LabelExpression())
                assertEquals(before, schemaSnapshot(connection))
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

    private data class Column(val name: String, val type: String, val notNull: Boolean, val primaryKeyPosition: Int)
    private data class TableSnapshot(val columns: List<Column>, val indexes: Set<String>, val foreignKeys: Set<String>)

    private fun schemaSnapshot(connection: java.sql.Connection): Map<String, TableSnapshot> = applicationTables(connection).sorted().associateWith { table ->
        val tableColumns = connection.createStatement().use { statement -> statement.executeQuery("PRAGMA table_info($table)").use { result ->
            buildList { while (result.next()) add(Column(result.getString("name"), result.getString("type"), result.getInt("notnull") == 1, result.getInt("pk"))) }
        } }
        val indexes = connection.createStatement().use { statement -> statement.executeQuery("PRAGMA index_list($table)").use { result ->
            buildSet { while (result.next()) { val name=result.getString("name"); val unique=result.getInt("unique"); val fields=connection.createStatement().use { nested -> nested.executeQuery("PRAGMA index_info($name)").use { rows -> buildList { while(rows.next()) add(rows.getString("name")) } } }; add("$name:$unique:${fields.joinToString(",")}") } }
        } }
        val foreignKeys = connection.createStatement().use { statement -> statement.executeQuery("PRAGMA foreign_key_list($table)").use { result ->
            buildSet { while(result.next()) add("${result.getString("from")}->${result.getString("table")}.${result.getString("to")}:${result.getString("on_delete")}") }
        } }
        TableSnapshot(tableColumns,indexes,foreignKeys)
    }

    private fun assertV2Manifest(schema: Map<String, TableSnapshot>) {
        val expectedFields = mapOf(
            "installation_configuration" to "singleton_id workspace_id api_base_url workspace_slug workspace_display_name workspace_web_url current_user_stable_id current_user_display_name configured_at retention_days",
            "configured_repository" to "id workspace_id slug display_name web_url removed_at position",
            "pull_request" to "id repository_id upstream_number title author_stable_id author_display_name draft head_commit web_url created_at updated_at observed_at active inactive_at readiness_kind readiness_passed readiness_total readiness_safe_reason builds_were_green",
            "readiness_check" to "pull_request_id position name passed safe_reason", "build_observation" to "pull_request_id position build_key state observed_at",
            "action_item" to "id pull_request_id repository_id source_kind upstream_source_id actor_stable_id actor_display_name activity_at observed_at activity_version state acknowledged_version acknowledged_at web_url",
            "synchronization_checkpoint" to "repository_id activity last_attempt_at last_attempt_outcome last_success_at snapshot_at problem_kind attempted_count succeeded_count consecutive_failure_count backoff_until pull_request_cursor activity_cursor",
            "synchronization_failure" to "repository_id position category retryable retry_at", "notification_intent" to "id delivery_key title body open_url sound created_at state attempt_count next_attempt_at",
            "notification_attempt" to "id intent_id attempt_number completed_at result_kind failure_category ambiguous",
        ).mapValues { it.value.split(" ") }
        assertEquals(expectedFields.keys + "bitbucket_connection_snapshot", schema.keys)
        expectedFields.forEach { (table, fields) -> assertEquals(fields, schema.getValue(table).columns.map { it.name }, table) }
        val notNullFalse = mapOf(
            "installation_configuration" to setOf("singleton_id"), "configured_repository" to setOf("id","removed_at"),
            "pull_request" to setOf("id","inactive_at","readiness_passed","readiness_total","readiness_safe_reason"), "readiness_check" to setOf("safe_reason"),
            "build_observation" to emptySet(), "action_item" to setOf("id","acknowledged_version","acknowledged_at"),
            "synchronization_checkpoint" to setOf("repository_id","last_attempt_at","last_attempt_outcome","last_success_at","snapshot_at","attempted_count","succeeded_count","backoff_until","pull_request_cursor","activity_cursor"),
            "synchronization_failure" to setOf("retry_at"), "notification_intent" to setOf("id","open_url","next_attempt_at"),
            "notification_attempt" to setOf("id","failure_category","ambiguous"),
        )
        expectedFields.keys.forEach { table -> assertEquals(notNullFalse.getValue(table), schema.getValue(table).columns.filterNot { it.notNull }.map { it.name }.toSet(), "$table nullability") }
        assertEquals(listOf(1,2), schema.getValue("readiness_check").columns.filter { it.primaryKeyPosition>0 }.map { it.primaryKeyPosition })
        assertEquals(listOf(1,2), schema.getValue("build_observation").columns.filter { it.primaryKeyPosition>0 }.map { it.primaryKeyPosition })
        assertEquals(listOf(1,2), schema.getValue("synchronization_failure").columns.filter { it.primaryKeyPosition>0 }.map { it.primaryKeyPosition })
        assertEquals(listOf(1), schema.getValue("notification_attempt").columns.filter { it.primaryKeyPosition>0 }.map { it.primaryKeyPosition })
        val expectedForeignKeys = expectedFields.keys.associateWith { emptySet<String>() }.toMutableMap().apply {
            this["configured_repository"]=setOf("workspace_id->installation_configuration.workspace_id:CASCADE")
            this["readiness_check"]=setOf("pull_request_id->pull_request.id:CASCADE"); this["build_observation"]=setOf("pull_request_id->pull_request.id:CASCADE")
            this["synchronization_failure"]=setOf("repository_id->synchronization_checkpoint.repository_id:CASCADE"); this["notification_attempt"]=setOf("intent_id->notification_intent.id:CASCADE")
        }
        expectedForeignKeys.forEach { (table, keys) -> assertEquals(keys, schema.getValue(table).foreignKeys, "$table foreign keys") }
        val expectedNamedIndexes=setOf(
            "configured_repository_workspace_position_idx:0:workspace_id,position,id", "pull_request_repository_active_id_idx:0:repository_id,active,id", "pull_request_inactive_at_id_idx:0:inactive_at,id",
            "action_item_pull_request_id_idx:0:pull_request_id,id", "action_item_actionable_idx:0:state,repository_id,pull_request_id,activity_at,id",
            "notification_intent_delivery_key_idx:0:delivery_key", "notification_intent_due_idx:0:state,next_attempt_at,created_at,id", "notification_attempt_intent_number_idx:0:intent_id,attempt_number,id",
        )
        assertEquals(expectedNamedIndexes, expectedFields.keys.flatMap { schema.getValue(it).indexes }.filterNot { it.startsWith("sqlite_autoindex_") }.toSet())
        val expectedUniqueColumns=mapOf(
            "installation_configuration" to setOf("workspace_id"), "configured_repository" to setOf("id"), "pull_request" to setOf("id"),
            "readiness_check" to setOf("pull_request_id,position"), "build_observation" to setOf("pull_request_id,position"), "action_item" to setOf("id"),
            "synchronization_checkpoint" to setOf("repository_id"), "synchronization_failure" to setOf("repository_id,position"), "notification_intent" to setOf("id"),
            "notification_attempt" to setOf("id","intent_id,attempt_number"),
        )
        expectedUniqueColumns.forEach { (table, fields) -> assertEquals(fields, schema.getValue(table).indexes.filter { it.substringAfter(':').startsWith("1:") }.map { it.substringAfter(':').substringAfter(':') }.toSet(), "$table unique keys") }
        schema.filterKeys { it != "bitbucket_connection_snapshot" }.values.flatMap { it.columns }.forEach { assertTrue(it.type == "TEXT" || it.type == "INTEGER") }
    }

    private fun assertOwnerOnly(path: Path) {
        assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "$path must be a regular file")
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS),
            "$path permissions",
        )
    }

    private companion object {
        const val NON_STICKY_SHARED_DIRECTORY_MODE = 0x1FF
        const val STICKY_SHARED_DIRECTORY_MODE = 0x3FF
        const val UNIX_MODE_MASK = 0xFFF
    }

    private data class ReplacementPolicyCase(
        val owner: String,
        val mode: Int,
        val childOwner: String,
        val expected: Boolean,
        val description: String,
    )
}
