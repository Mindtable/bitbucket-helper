package com.mindtable.bitbuckethelper.application.port.inbound

import com.mindtable.bitbuckethelper.application.model.BitbucketConnectionSnapshot

fun interface GetBitbucketConnectionStatus {
    suspend operator fun invoke(): BitbucketConnectionSnapshot?
}
