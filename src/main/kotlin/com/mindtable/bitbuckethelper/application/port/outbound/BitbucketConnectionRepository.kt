package com.mindtable.bitbuckethelper.application.port.outbound

import com.mindtable.bitbuckethelper.application.model.BitbucketAccount
import com.mindtable.bitbuckethelper.application.model.BitbucketConnectionSnapshot
import com.mindtable.bitbuckethelper.application.model.ConnectionFailure
import java.time.Instant

interface BitbucketConnectionRepository {
    suspend fun find(): BitbucketConnectionSnapshot?
    suspend fun recordSuccess(account: BitbucketAccount, attemptedAt: Instant): BitbucketConnectionSnapshot
    suspend fun recordFailure(failure: ConnectionFailure, attemptedAt: Instant): BitbucketConnectionSnapshot
}
