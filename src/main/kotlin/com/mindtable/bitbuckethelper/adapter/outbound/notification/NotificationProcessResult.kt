package com.mindtable.bitbuckethelper.adapter.outbound.notification

sealed interface NotificationProcessResult {
    val stdout: ByteArray
    val stderr: ByteArray
    val stdoutOverflowed: Boolean
    val stderrOverflowed: Boolean

    data class Exited(
        val exitCode: Int,
        val derivedSignal: Int?,
        override val stdout: ByteArray,
        override val stderr: ByteArray,
        override val stdoutOverflowed: Boolean,
        override val stderrOverflowed: Boolean,
    ) : NotificationProcessResult

    data class TimedOut(
        override val stdout: ByteArray,
        override val stderr: ByteArray,
        override val stdoutOverflowed: Boolean,
        override val stderrOverflowed: Boolean,
    ) : NotificationProcessResult
}
