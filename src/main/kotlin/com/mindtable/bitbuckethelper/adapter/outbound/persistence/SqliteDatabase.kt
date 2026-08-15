package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import java.nio.file.Files
import java.nio.file.Path
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

class SqliteDatabase private constructor(
    val dataSource: SQLiteDataSource,
) : AutoCloseable {
    fun migrate() {
        dataSource.connection.use { connection ->
            Liquibase(
                "db/changelog/db.changelog-master.xml",
                ClassLoaderResourceAccessor(),
                JdbcConnection(connection),
            ).use { liquibase -> liquibase.update(Contexts(), LabelExpression()) }
        }
    }

    override fun close() = Unit

    companion object {
        fun open(path: Path): SqliteDatabase {
            Files.createDirectories(requireNotNull(path.parent))
            val sqlite = SQLiteConfig().apply {
                enforceForeignKeys(true)
                setBusyTimeout(5_000)
            }
            val source = SQLiteDataSource(sqlite).apply {
                url = "jdbc:sqlite:${path.toAbsolutePath().normalize()}"
            }
            return SqliteDatabase(source)
        }
    }
}
