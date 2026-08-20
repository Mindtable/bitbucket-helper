package com.mindtable.bitbuckethelper.bootstrap

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions

internal object SecureLoggingDirectory {
    private const val SETTING = "BITBUCKET_HELPER_LOG_DIRECTORY"
    private val OWNER_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")

    fun prepare(path: Path): Path {
        val normalized = normalize(path)
        val parent = normalized.parent
            ?: throw StartupConfigurationException("$SETTING parent must be a real directory")
        val canonicalParent = validateParent(parent)
        val fileName = normalized.fileName
            ?: throw StartupConfigurationException("$SETTING must identify a directory")
        val canonicalPath = canonicalParent.resolve(fileName.toString())

        if (Files.exists(canonicalPath, LinkOption.NOFOLLOW_LINKS)) {
            validateFinalDirectory(canonicalPath)
        } else {
            createFinalDirectory(canonicalParent, fileName, canonicalPath)
        }
        return normalized
    }

    private fun normalize(path: Path): Path = try {
        path.toAbsolutePath().normalize()
    } catch (_: Exception) {
        throw StartupConfigurationException("$SETTING must identify a valid path")
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

    private fun createFinalDirectory(parent: Path, fileName: Path, path: Path) {
        try {
            openSecureDirectory(parent).use { directory ->
                try {
                    Files.createDirectory(
                        path,
                        PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY_PERMISSIONS),
                    )
                } catch (_: FileAlreadyExistsException) {
                    validateFinalDirectory(path)
                    return
                }
            }
        } catch (failure: StartupConfigurationException) {
            throw failure
        } catch (_: Exception) {
            throw StartupConfigurationException("$SETTING final directory could not be created safely")
        }
        validateFinalDirectory(path)
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
