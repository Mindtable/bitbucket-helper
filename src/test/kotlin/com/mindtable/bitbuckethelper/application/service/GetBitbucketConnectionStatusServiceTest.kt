package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.BitbucketAccount
import com.mindtable.bitbuckethelper.application.model.BitbucketConnectionSnapshot
import com.mindtable.bitbuckethelper.application.model.ConnectionFailure
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketConnectionRepository
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GetBitbucketConnectionStatusServiceTest {
    @Test
    fun `query returns null when no snapshot exists`() = runTest {
        val repository = FakeConnectionRepository()
        val query = GetBitbucketConnectionStatusService(repository)

        assertNull(query())
    }

    private class FakeConnectionRepository : BitbucketConnectionRepository {
        override suspend fun find(): BitbucketConnectionSnapshot? = null

        override suspend fun recordSuccess(
            account: BitbucketAccount,
            attemptedAt: Instant,
        ): BitbucketConnectionSnapshot = error("recordSuccess is not used by status queries")

        override suspend fun recordFailure(
            failure: ConnectionFailure,
            attemptedAt: Instant,
        ): BitbucketConnectionSnapshot = error("recordFailure is not used by status queries")
    }
}
