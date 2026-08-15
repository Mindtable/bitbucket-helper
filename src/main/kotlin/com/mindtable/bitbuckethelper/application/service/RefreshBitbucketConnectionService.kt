package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.BitbucketAccountResult
import com.mindtable.bitbuckethelper.application.model.BitbucketConnectionSnapshot
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshBitbucketConnection
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketAccountGateway
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketConnectionRepository
import java.time.Clock

class RefreshBitbucketConnectionService(
    private val gateway: BitbucketAccountGateway,
    private val repository: BitbucketConnectionRepository,
    private val clock: Clock,
) : RefreshBitbucketConnection {
    override suspend fun invoke(): BitbucketConnectionSnapshot {
        val attemptedAt = clock.instant()
        return when (val result = gateway.fetchCurrentAccount()) {
            is BitbucketAccountResult.Success -> repository.recordSuccess(result.account, attemptedAt)
            is BitbucketAccountResult.Failure -> repository.recordFailure(result.failure, attemptedAt)
        }
    }
}
