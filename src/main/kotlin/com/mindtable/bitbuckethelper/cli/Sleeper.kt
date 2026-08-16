package com.mindtable.bitbuckethelper.cli

import kotlinx.coroutines.delay

/** Wait seam used by bounded refresh polling. */
fun interface Sleeper {
    suspend fun sleep(milliseconds: Long)
}

object CoroutineSleeper : Sleeper {
    override suspend fun sleep(milliseconds: Long) {
        delay(milliseconds)
    }
}
