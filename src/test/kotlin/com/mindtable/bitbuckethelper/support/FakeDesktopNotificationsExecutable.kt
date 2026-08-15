package com.mindtable.bitbuckethelper.support

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
import java.nio.file.attribute.PosixFilePermission.GROUP_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OTHERS_READ

object FakeDesktopNotificationsExecutable {
    fun create(directory: Path, script: String): Path {
        val executable = Files.createTempFile(directory, "fake-desktop-notifications-", ".sh")
        Files.writeString(executable, "#!/bin/sh\n$script\n", UTF_8)
        Files.setPosixFilePermissions(
            executable,
            setOf(
                OWNER_READ,
                OWNER_WRITE,
                OWNER_EXECUTE,
                GROUP_READ,
                GROUP_EXECUTE,
                OTHERS_READ,
                OTHERS_EXECUTE,
            ),
        )
        return executable.toAbsolutePath().normalize()
    }
}
