package com.mindtable.bitbuckethelper.adapter.inbound.http

import java.nio.file.Path

data class LocalApiServerConfiguration(
    val host: String,
    val port: Int,
    val socketPath: Path,
) {
    init {
        require(host == LOOPBACK_HOST) { "The browser API host must be the explicit IPv4 loopback address" }
        require(port in 0..MAX_PORT) { "The browser API port is outside the valid range" }
        require(socketPath.isAbsolute) { "The Unix socket path must be absolute" }
        require(socketPath.normalize() == socketPath) { "The Unix socket path must be normalized" }
        require(socketPath.parent != null) { "The Unix socket path must have a parent directory" }
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val MAX_PORT = 65_535
    }
}
