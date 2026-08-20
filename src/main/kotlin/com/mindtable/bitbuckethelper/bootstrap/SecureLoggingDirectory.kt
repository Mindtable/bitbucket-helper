package com.mindtable.bitbuckethelper.bootstrap

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal object SecureLoggingDirectory {
    private const val SETTING = "BITBUCKET_HELPER_LOG_DIRECTORY"
    private val OWNER_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")

    @Volatile
    internal var beforeStagingDirectoryCreateForTest: ((Path) -> Unit)? = null

    @Volatile
    internal var beforeSecurePromotionForTest: ((Path) -> Unit)? = null

    @Volatile
    internal var afterFinalDirectoryCreateForTest: ((Path) -> Unit)? = null

    fun prepare(path: Path): Path {
        val normalized = normalize(path)
        validateLexicalComponents(normalized)
        val parent = normalized.parent
            ?: throw StartupConfigurationException("$SETTING parent must be a real directory")
        val canonicalParent = validateParent(parent)
        val fileName = normalized.fileName
            ?: throw StartupConfigurationException("$SETTING must identify a directory")
        val canonicalPath = canonicalParent.resolve(fileName.toString())

        if (Files.exists(canonicalPath, LinkOption.NOFOLLOW_LINKS)) {
            validateFinalDirectory(canonicalPath)
            return canonicalFinalPath(canonicalPath)
        } else {
            return createFinalDirectory(canonicalParent, fileName, canonicalPath)
        }
    }

    private fun normalize(path: Path): Path = try {
        path.toAbsolutePath().normalize()
    } catch (_: Exception) {
        throw StartupConfigurationException("$SETTING must identify a valid path")
    }

    private fun validateLexicalComponents(path: Path) {
        val root = path.root
            ?: throw StartupConfigurationException("$SETTING must identify an absolute path")
        var current = root
        val rootAttributes = readAttributes(current)
        requireDirectory(rootAttributes, "ancestors must be real directories")
        path.forEach { component ->
            current = current.resolve(component)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (current != path) {
                    throw StartupConfigurationException("$SETTING intermediate directories must exist")
                }
                return
            }
            val attributes = readAttributes(current)
            if (attributes.isSymbolicLink) {
                throw StartupConfigurationException("$SETTING path components must not be symbolic links")
            }
            if (current != path && !attributes.isDirectory) {
                throw StartupConfigurationException("$SETTING ancestors must be real directories")
            }
        }
    }

    private fun validateParent(parent: Path): Path {
        val directAttributes = readAttributes(parent)
        requireDirectory(directAttributes, "parent must be a real directory")
        requireCurrentUser(directAttributes, "parent must be owned by the current user")
        requireOwnerOnlyWritable(directAttributes, "parent must use owner-only writable permissions")
        requireWritable(parent, "parent must be writable")

        val canonicalParent = try {
            parent.toRealPath()
        } catch (_: Exception) {
            throw StartupConfigurationException("$SETTING parent path is unavailable")
        }
        val canonicalAttributes = readAttributes(canonicalParent)
        requireDirectory(canonicalAttributes, "parent must be a real directory")
        requireCurrentUser(canonicalAttributes, "parent must be owned by the current user")
        requireOwnerOnlyWritable(canonicalAttributes, "parent must use owner-only writable permissions")
        requireWritable(canonicalParent, "parent must be writable")
        requireSecureDirectoryAccess(canonicalParent)
        validateReplacementSafeAncestors(canonicalParent)
        return canonicalParent
    }

    private fun createFinalDirectory(parent: Path, fileName: Path, path: Path): Path {
        try {
            openSecureDirectory(parent).use { directory ->
                val expectedParentIdentity = captureParentIdentity(parent, directory)
                revalidateParentIdentity(parent, directory, expectedParentIdentity)
                val stagingName = Path.of(".bitbucket-helper-log-staging-${UUID.randomUUID()}")
                val stagingPath = parent.resolve(stagingName)
                beforeStagingDirectoryCreateForTest?.invoke(stagingPath)

                var staged = false
                var promoted = false
                var stagingIdentity: DirectoryIdentity? = null
                var finalIdentity: DirectoryIdentity? = null
                try {
                    Files.createDirectory(
                        stagingPath,
                        PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY_PERMISSIONS),
                    )
                    staged = true
                    stagingIdentity = secureChildIdentity(directory, stagingName)
                    revalidateParentIdentity(parent, directory, expectedParentIdentity)
                    beforeSecurePromotionForTest?.invoke(path)
                    directory.move(stagingName, directory, fileName)
                    promoted = true
                    finalIdentity = secureChildIdentity(directory, fileName)
                    if (finalIdentity != stagingIdentity) {
                        throw StartupConfigurationException("$SETTING final directory identity changed")
                    }
                    afterFinalDirectoryCreateForTest?.invoke(path)
                    revalidateParentIdentity(parent, directory, expectedParentIdentity)
                    val canonicalPath = canonicalFinalPath(path)
                    validateFinalDirectory(canonicalPath)
                    return canonicalPath
                } catch (failure: FileAlreadyExistsException) {
                    if (!staged || stagingIdentity == null) throw failure
                    var stagingCleaned = false
                    try {
                        revalidateParentIdentity(parent, directory, expectedParentIdentity)
                        deleteCreatedDirectory(directory, stagingName, stagingIdentity)
                        stagingCleaned = true
                        validateFinalDirectory(path)
                        return canonicalFinalPath(path)
                    } catch (failure: Exception) {
                        if (!stagingCleaned) {
                            runCatching {
                                deleteCreatedDirectory(directory, stagingName, stagingIdentity)
                            }.onFailure(failure::addSuppressed)
                        }
                        throw failure
                    }
                } catch (failure: Exception) {
                    if (promoted && finalIdentity != null) {
                        runCatching {
                            deleteCreatedDirectory(directory, fileName, finalIdentity)
                        }.onFailure(failure::addSuppressed)
                    } else if (stagingIdentity != null) {
                        runCatching {
                            deleteCreatedDirectory(directory, stagingName, stagingIdentity)
                        }.onFailure(failure::addSuppressed)
                    }
                    throw failure
                }
            }
        } catch (failure: StartupConfigurationException) {
            throw failure
        } catch (_: Exception) {
            throw StartupConfigurationException("$SETTING final directory could not be created safely")
        }
    }

    private fun canonicalFinalPath(path: Path): Path = try {
        path.toRealPath()
    } catch (_: Exception) {
        throw StartupConfigurationException("$SETTING final directory path is unavailable")
    }

    private fun captureParentIdentity(
        parent: Path,
        directory: SecureDirectoryStream<Path>,
    ): DirectoryIdentity {
        val pathAttributes = readAttributes(parent)
        val handleAttributes = secureDirectoryAttributes(directory)
        validateParentAttributes(parent, pathAttributes)
        validateParentAttributes(null, handleAttributes)
        val pathIdentity = directoryIdentity(pathAttributes)
        val handleIdentity = directoryIdentity(handleAttributes)
        if (pathIdentity != handleIdentity) {
            throw StartupConfigurationException("$SETTING parent identity changed")
        }
        return pathIdentity
    }

    private fun revalidateParentIdentity(
        parent: Path,
        directory: SecureDirectoryStream<Path>,
        expected: DirectoryIdentity,
    ) {
        val pathAttributes = readAttributes(parent)
        val handleAttributes = secureDirectoryAttributes(directory)
        validateParentAttributes(parent, pathAttributes)
        validateParentAttributes(null, handleAttributes)
        val pathIdentity = directoryIdentity(pathAttributes)
        val handleIdentity = directoryIdentity(handleAttributes)
        if (pathIdentity != expected || handleIdentity != expected || pathIdentity != handleIdentity) {
            throw StartupConfigurationException("$SETTING parent identity changed")
        }
        validateReplacementSafeAncestors(parent)
    }

    private fun validateParentAttributes(
        path: Path?,
        attributes: PosixFileAttributes,
    ) {
        requireDirectory(attributes, "parent must be a real directory")
        requireCurrentUser(attributes, "parent must be owned by the current user")
        requireOwnerOnlyWritable(attributes, "parent must use owner-only writable permissions")
        if (path != null) requireWritable(path, "parent must be writable")
    }

    private fun secureDirectoryAttributes(
        directory: SecureDirectoryStream<Path>,
    ): PosixFileAttributes {
        val view = directory.getFileAttributeView(PosixFileAttributeView::class.java)
            ?: throw StartupConfigurationException("$SETTING parent identity is unavailable")
        return try {
            view.readAttributes()
        } catch (_: Exception) {
            throw StartupConfigurationException("$SETTING parent identity is unavailable")
        }
    }

    private fun secureChildIdentity(
        directory: SecureDirectoryStream<Path>,
        fileName: Path,
    ): DirectoryIdentity {
        val view = directory.getFileAttributeView(
            fileName,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: throw StartupConfigurationException("$SETTING final directory identity is unavailable")
        val attributes = try {
            view.readAttributes()
        } catch (_: Exception) {
            throw StartupConfigurationException("$SETTING final directory identity is unavailable")
        }
        return directoryIdentity(attributes)
    }

    private fun directoryIdentity(attributes: PosixFileAttributes): DirectoryIdentity {
        val fileKey = attributes.fileKey()
            ?: throw StartupConfigurationException("$SETTING directory identity is unavailable")
        return DirectoryIdentity(
            fileKey = fileKey,
            ownerName = attributes.owner().name,
            permissions = attributes.permissions(),
            isDirectory = attributes.isDirectory && !attributes.isSymbolicLink,
        )
    }

    private fun deleteCreatedDirectory(
        directory: SecureDirectoryStream<Path>,
        fileName: Path,
        expected: DirectoryIdentity,
    ) {
        val current = secureChildIdentity(directory, fileName)
        if (current != expected || !current.isDirectory) {
            throw StartupConfigurationException("$SETTING final directory identity changed")
        }
        directory.deleteDirectory(fileName)
    }

    private fun validateFinalDirectory(path: Path) {
        val attributes = readAttributes(path)
        requireDirectory(attributes, "must identify a real directory")
        requireCurrentUser(attributes, "must be owned by the current user")
        requireOwnerOnlyWritable(attributes, "must use owner-only writable permissions")
        requireWritable(path, "must be writable")
        requireSecureDirectoryAccess(path)
    }

    private fun openSecureDirectory(path: Path): SecureDirectoryStream<Path> {
        try {
            val stream = Files.newDirectoryStream(path)
            if (stream !is SecureDirectoryStream<*>) {
                stream.close()
                throw StartupConfigurationException("$SETTING directory must support secure directory access")
            }
            @Suppress("UNCHECKED_CAST")
            return stream as SecureDirectoryStream<Path>
        } catch (failure: StartupConfigurationException) {
            throw failure
        } catch (_: Exception) {
            throw StartupConfigurationException("$SETTING directory secure access is unavailable")
        }
    }

    private fun requireSecureDirectoryAccess(path: Path) {
        openSecureDirectory(path).use { }
    }

    private fun readAttributes(path: Path): PosixFileAttributes = try {
        Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        throw StartupConfigurationException("$SETTING path attributes are unavailable")
    }

    private fun requireDirectory(attributes: PosixFileAttributes, detail: String) {
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            throw StartupConfigurationException("$SETTING $detail")
        }
    }

    private fun requireCurrentUser(attributes: PosixFileAttributes, detail: String) {
        val currentUser = System.getProperty("user.name")
        val owner = attributes.owner().name
        if (owner != currentUser && owner.substringAfterLast('\\') != currentUser) {
            throw StartupConfigurationException("$SETTING $detail")
        }
    }

    private fun requireOwnerOnlyWritable(attributes: PosixFileAttributes, detail: String) {
        if (attributes.permissions() != OWNER_DIRECTORY_PERMISSIONS) {
            throw StartupConfigurationException("$SETTING $detail")
        }
    }

    private fun requireWritable(path: Path, detail: String) {
        if (!Files.isWritable(path)) {
            throw StartupConfigurationException("$SETTING $detail")
        }
    }

    private data class DirectoryIdentity(
        val fileKey: Any,
        val ownerName: String,
        val permissions: Set<PosixFilePermission>,
        val isDirectory: Boolean,
    )

    private fun validateReplacementSafeAncestors(managedParent: Path) {
        var directChildOwnerName = readAttributes(managedParent).owner().name
        var ancestor = managedParent.parent
        while (ancestor != null) {
            val attributes = readAttributes(ancestor)
            if (!attributes.isDirectory || attributes.isSymbolicLink) {
                throw StartupConfigurationException("$SETTING ancestors must be real directories")
            }
            val mode = try {
                Files.getAttribute(ancestor, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int
            } catch (_: Exception) {
                throw StartupConfigurationException("$SETTING ancestor mode is unavailable")
            }
            if (!isReplacementSafeAncestor(
                    ownerName = attributes.owner().name,
                    currentUserName = System.getProperty("user.name"),
                    unixMode = mode,
                    directChildOwnerName = directChildOwnerName,
                )
            ) {
                throw StartupConfigurationException("$SETTING ancestors must not be replaceable by another user")
            }
            directChildOwnerName = attributes.owner().name
            ancestor = ancestor.parent
        }
    }
}
