package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
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
            val privatePath = SqliteFileSecurity.prepare(path)
            val sqlite = SQLiteConfig().apply {
                enforceForeignKeys(true)
                setBusyTimeout(5_000)
            }
            val source = SQLiteDataSource(sqlite).apply {
                url = "jdbc:sqlite:$privatePath"
            }
            return SqliteDatabase(source)
        }
    }
}

private object SqliteFileSecurity {
    private val ownerDirectoryPermissions = PosixFilePermissions.fromString("rwx------")
    private val ownerFilePermissions = PosixFilePermissions.fromString("rw-------")

    fun prepare(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        val parent = requireNotNull(normalized.parent) { "SQLite database parent is required" }
        val directoryPlan = planPrivateDirectory(parent)
        val prospectivePath = directoryPlan.target.resolve(normalized.fileName)
        validateExistingSidecars(prospectivePath)
        val privateParent = preparePrivateDirectory(directoryPlan)
        val privatePath = privateParent.resolve(normalized.fileName)
        validateExistingSidecars(privatePath)

        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(normalized)) {
                "SQLite database must be a regular non-symbolic-link file"
            }
            validateDatabaseFile(privatePath)
        } else {
            try {
                Files.newByteChannel(
                    privatePath,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(ownerFilePermissions),
                ).use { }
            } catch (_: FileAlreadyExistsException) {
                // Another creator won the race. It is accepted only after the same strict validation.
            }
            validateDatabaseFile(privatePath)
        }
        validateExistingSidecars(privatePath)
        validatePrivateDirectory(privateParent)
        return privatePath
    }

    private fun planPrivateDirectory(path: Path): PrivateDirectoryPlan {
        val missing = ArrayDeque<Path>()
        var ancestor = path
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(ancestor.fileName)
            ancestor = requireNotNull(ancestor.parent) { "SQLite database parent is unavailable" }
        }
        require(!Files.isSymbolicLink(ancestor)) {
            "SQLite database parent must be a real directory"
        }
        var canonicalDirectory = try {
            ancestor.toRealPath()
        } catch (_: Exception) {
            throw IllegalArgumentException("SQLite database parent path is unavailable")
        }
        validatePrivateDirectory(canonicalDirectory)
        validateReplacementSafeAncestors(canonicalDirectory)
        return PrivateDirectoryPlan(canonicalDirectory, missing.toList())
    }

    private fun preparePrivateDirectory(plan: PrivateDirectoryPlan): Path {
        var canonicalDirectory = plan.existingAncestor
        for (component in plan.missingComponents) {
            val directory = canonicalDirectory.resolve(component)
            try {
                Files.createDirectory(
                    directory,
                    PosixFilePermissions.asFileAttribute(ownerDirectoryPermissions),
                )
            } catch (_: FileAlreadyExistsException) {
                // A racing path is trusted only if it is still a private real directory.
            }
            validatePrivateDirectory(directory)
            canonicalDirectory = directory
        }
        return canonicalDirectory
    }

    private fun validateDatabaseFile(path: Path) {
        val attributes = readAttributes(path)
        require(attributes.isRegularFile && !attributes.isSymbolicLink) {
            "SQLite database must be a regular non-symbolic-link file"
        }
        require(attributes.owner().isCurrentUser()) {
            "SQLite database must be owned by the current user"
        }
        require(attributes.permissions() == ownerFilePermissions && Files.isWritable(path)) {
            "SQLite database must use owner-only writable permissions"
        }
    }

    private fun validateExistingSidecars(databasePath: Path) {
        listOf("-journal", "-wal", "-shm").forEach { suffix ->
            val sidecar = databasePath.resolveSibling(databasePath.fileName.toString() + suffix)
            if (!Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) return@forEach
            val attributes = readAttributes(sidecar)
            require(attributes.isRegularFile && !attributes.isSymbolicLink) {
                "SQLite sidecar must be a regular non-symbolic-link file"
            }
            require(attributes.owner().isCurrentUser()) {
                "SQLite sidecar must be owned by the current user"
            }
            require(attributes.permissions() == ownerFilePermissions && Files.isWritable(sidecar)) {
                "SQLite sidecar must use owner-only writable permissions"
            }
        }
    }

    private fun validatePrivateDirectory(path: Path) {
        val attributes = readAttributes(path)
        require(attributes.isDirectory && !attributes.isSymbolicLink) {
            "SQLite database parent must be a real directory"
        }
        require(attributes.owner().isCurrentUser()) {
            "SQLite database parent must be owned by the current user"
        }
        require(attributes.permissions() == ownerDirectoryPermissions && Files.isWritable(path)) {
            "SQLite database parent must use owner-only writable permissions"
        }
        Files.newDirectoryStream(path).use { directory ->
            require(directory is SecureDirectoryStream<*>) {
                "SQLite database parent must support secure directory access"
            }
        }
    }

    private fun validateReplacementSafeAncestors(managedParent: Path) {
        var directChildOwnerName = readAttributes(managedParent).owner().name
        var ancestor = managedParent.parent
        while (ancestor != null) {
            val attributes = readAttributes(ancestor)
            require(attributes.isDirectory && !attributes.isSymbolicLink) {
                "SQLite database ancestors must be real directories"
            }
            val mode = try {
                Files.getAttribute(ancestor, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int
            } catch (_: Exception) {
                throw IllegalArgumentException("SQLite database ancestor mode is unavailable")
            }
            require(
                isReplacementSafeAncestor(
                    ownerName = attributes.owner().name,
                    currentUserName = System.getProperty("user.name"),
                    unixMode = mode,
                    directChildOwnerName = directChildOwnerName,
                ),
            ) {
                "SQLite database ancestors must not be replaceable by another user"
            }
            directChildOwnerName = attributes.owner().name
            ancestor = ancestor.parent
        }
    }

    private fun readAttributes(path: Path): PosixFileAttributes = try {
        Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        throw IllegalArgumentException("SQLite database path attributes are unavailable")
    }

    private fun java.nio.file.attribute.UserPrincipal.isCurrentUser(): Boolean {
        val currentUser = System.getProperty("user.name")
        return name == currentUser || name.substringAfterLast('\\') == currentUser
    }

    private data class PrivateDirectoryPlan(
        val existingAncestor: Path,
        val missingComponents: List<Path>,
    ) {
        val target: Path = missingComponents.fold(existingAncestor) { current, component ->
            current.resolve(component)
        }
    }

}

internal fun isReplacementSafeAncestor(
    ownerName: String,
    currentUserName: String,
    unixMode: Int,
    directChildOwnerName: String,
): Boolean {
    fun trusted(name: String): Boolean {
        val normalized = name.substringAfterLast('\\')
        return normalized == "root" || normalized == currentUserName.substringAfterLast('\\')
    }

    if (!trusted(ownerName)) return false
    val writableByAnotherUser = unixMode and 0x12 != 0
    if (!writableByAnotherUser) return true
    val sticky = unixMode and 0x200 != 0
    return sticky && trusted(directChildOwnerName)
}
