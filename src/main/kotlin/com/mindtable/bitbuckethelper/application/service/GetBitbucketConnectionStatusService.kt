package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.BitbucketConnectionSnapshot
import com.mindtable.bitbuckethelper.application.port.inbound.GetBitbucketConnectionStatus
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketConnectionRepository

class GetBitbucketConnectionStatusService(
    private val repository: BitbucketConnectionRepository,
) : GetBitbucketConnectionStatus {
    override suspend fun invoke(): BitbucketConnectionSnapshot? = repository.find()
}
