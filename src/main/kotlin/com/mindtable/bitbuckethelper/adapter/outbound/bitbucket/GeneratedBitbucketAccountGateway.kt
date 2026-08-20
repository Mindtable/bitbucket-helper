package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import com.mindtable.bitbuckethelper.application.model.BitbucketAccount
import com.mindtable.bitbuckethelper.application.model.BitbucketAccountResult
import com.mindtable.bitbuckethelper.application.model.ConnectionFailure
import com.mindtable.bitbuckethelper.application.model.ConnectionFailureCode
import com.mindtable.bitbuckethelper.application.model.GatewayFailureCategory
import com.mindtable.bitbuckethelper.application.model.GatewayResult
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketAccountGateway
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import io.ktor.client.engine.HttpClientEngine
import java.net.URI
import java.time.Duration

class GeneratedBitbucketAccountGateway private constructor(
    private val baseUrl: URI,
    private val delegate: GeneratedBitbucketGateway,
) : BitbucketAccountGateway, AutoCloseable {
    override suspend fun fetchCurrentAccount(): BitbucketAccountResult = when (val result = delegate.currentUser(baseUrl)) {
        is GatewayResult.Success -> BitbucketAccountResult.Success(
            BitbucketAccount(
                uuid = result.value.stableId,
                displayName = result.value.displayName,
                nickname = result.value.nickname,
            ),
        )
        GatewayResult.NotFound -> unexpectedFailure()
        is GatewayResult.Failure -> legacyFailure(result.failure.category)
    }

    override fun close() = delegate.close()

    private fun legacyFailure(category: GatewayFailureCategory): BitbucketAccountResult.Failure = when (category) {
        GatewayFailureCategory.AUTHENTICATION -> failure(
            ConnectionFailureCode.AUTHENTICATION,
            "Bitbucket rejected the credentials",
        )
        GatewayFailureCategory.AUTHORIZATION -> failure(
            ConnectionFailureCode.AUTHORIZATION,
            "Bitbucket denied the required permission",
        )
        GatewayFailureCategory.RATE_LIMITED -> failure(
            ConnectionFailureCode.RATE_LIMITED,
            "Bitbucket rate limit exceeded",
        )
        GatewayFailureCategory.TIMEOUT -> failure(ConnectionFailureCode.TIMEOUT, "Bitbucket request timed out")
        GatewayFailureCategory.NETWORK -> failure(ConnectionFailureCode.NETWORK, "Bitbucket is unreachable")
        GatewayFailureCategory.UPSTREAM -> failure(ConnectionFailureCode.UPSTREAM, "Bitbucket service failed")
        GatewayFailureCategory.MALFORMED_RESPONSE,
        GatewayFailureCategory.UNSAFE_PAGINATION,
        -> unexpectedFailure()
    }

    private fun unexpectedFailure(): BitbucketAccountResult.Failure =
        failure(ConnectionFailureCode.UNEXPECTED, "Bitbucket request failed unexpectedly")

    private fun failure(
        code: ConnectionFailureCode,
        message: String,
    ): BitbucketAccountResult.Failure = BitbucketAccountResult.Failure(ConnectionFailure(code, message))

    companion object {
        fun create(
            baseUrl: URI,
            requestTimeout: Duration,
            username: String,
            apiToken: String,
            recorder: BackendEventRecorder = BackendEventRecorder.NONE,
            timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
        ): GeneratedBitbucketAccountGateway =
            GeneratedBitbucketAccountGateway(
                baseUrl,
                GeneratedBitbucketGateway.create(
                    requestTimeout,
                    username,
                    apiToken,
                    recorder = recorder,
                    timeSource = timeSource,
                ),
            )

        internal fun create(
            baseUrl: URI,
            requestTimeout: Duration,
            username: String,
            apiToken: String,
            engine: HttpClientEngine,
            recorder: BackendEventRecorder = BackendEventRecorder.NONE,
            timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
        ): GeneratedBitbucketAccountGateway =
            GeneratedBitbucketAccountGateway(
                baseUrl,
                GeneratedBitbucketGateway.create(
                    requestTimeout,
                    username,
                    apiToken,
                    engine,
                    recorder = recorder,
                    timeSource = timeSource,
                ),
            )
    }
}
