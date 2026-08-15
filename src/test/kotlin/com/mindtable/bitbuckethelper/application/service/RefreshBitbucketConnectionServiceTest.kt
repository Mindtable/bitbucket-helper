package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.BitbucketAccount
import com.mindtable.bitbuckethelper.application.model.BitbucketAccountResult
import com.mindtable.bitbuckethelper.application.model.BitbucketConnectionSnapshot
import com.mindtable.bitbuckethelper.application.model.ConnectionFailure
import com.mindtable.bitbuckethelper.application.model.ConnectionFailureCode
import com.mindtable.bitbuckethelper.application.model.ConnectionState
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketAccountGateway
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketConnectionRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RefreshBitbucketConnectionServiceTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-15T10:15:30Z"), ZoneOffset.UTC)

    @Test
    fun `success persists a healthy snapshot at the clock instant`() = runTest {
        val account = BitbucketAccount("{account-uuid}", "Ada Lovelace", null)
        val repository = FakeConnectionRepository()
        val service = RefreshBitbucketConnectionService(
            gateway = BitbucketAccountGateway { BitbucketAccountResult.Success(account) },
            repository = repository,
            clock = fixedClock,
        )

        val result = service()

        assertEquals(ConnectionState.HEALTHY, result.state)
        assertEquals(account, result.account)
        assertEquals(Instant.parse("2026-08-15T10:15:30Z"), result.lastAttemptAt)
        assertEquals(result.lastAttemptAt, result.lastSuccessAt)
        assertNull(result.failure)
    }

    @Test
    fun `failure records a sanitized category and delegates preservation to the repository`() = runTest {
        val failure = ConnectionFailure(ConnectionFailureCode.RATE_LIMITED, "Bitbucket rate limit exceeded")
        val repository = FakeConnectionRepository(existingHealthySnapshot())
        val service = RefreshBitbucketConnectionService(
            gateway = BitbucketAccountGateway { BitbucketAccountResult.Failure(failure) },
            repository = repository,
            clock = fixedClock,
        )

        val result = service()

        assertEquals(ConnectionState.FAILED, result.state)
        assertEquals(existingHealthySnapshot().account, result.account)
        assertEquals(existingHealthySnapshot().lastSuccessAt, result.lastSuccessAt)
        assertEquals(failure, result.failure)
    }

    private class FakeConnectionRepository(
        initialSnapshot: BitbucketConnectionSnapshot? = null,
    ) : BitbucketConnectionRepository {
        private var snapshot = initialSnapshot

        override suspend fun find(): BitbucketConnectionSnapshot? = snapshot

        override suspend fun recordSuccess(
            account: BitbucketAccount,
            attemptedAt: Instant,
        ): BitbucketConnectionSnapshot = BitbucketConnectionSnapshot(
            state = ConnectionState.HEALTHY,
            account = account,
            lastAttemptAt = attemptedAt,
            lastSuccessAt = attemptedAt,
            failure = null,
        ).also { snapshot = it }

        override suspend fun recordFailure(
            failure: ConnectionFailure,
            attemptedAt: Instant,
        ): BitbucketConnectionSnapshot = BitbucketConnectionSnapshot(
            state = ConnectionState.FAILED,
            account = snapshot?.account,
            lastAttemptAt = attemptedAt,
            lastSuccessAt = snapshot?.lastSuccessAt,
            failure = failure,
        ).also { snapshot = it }
    }

    private fun existingHealthySnapshot(): BitbucketConnectionSnapshot = BitbucketConnectionSnapshot(
        state = ConnectionState.HEALTHY,
        account = BitbucketAccount("{existing-account-uuid}", "Grace Hopper", "amazing-grace"),
        lastAttemptAt = Instant.parse("2026-08-15T10:00:00Z"),
        lastSuccessAt = Instant.parse("2026-08-15T10:00:00Z"),
        failure = null,
    )
}
