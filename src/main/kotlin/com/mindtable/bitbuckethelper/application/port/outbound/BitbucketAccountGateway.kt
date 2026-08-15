package com.mindtable.bitbuckethelper.application.port.outbound

import com.mindtable.bitbuckethelper.application.model.BitbucketAccountResult

fun interface BitbucketAccountGateway {
    suspend fun fetchCurrentAccount(): BitbucketAccountResult
}
