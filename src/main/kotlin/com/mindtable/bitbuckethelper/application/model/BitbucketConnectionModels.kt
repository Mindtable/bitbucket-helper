package com.mindtable.bitbuckethelper.application.model

import java.time.Instant

data class BitbucketAccount(
    val uuid: String,
    val displayName: String,
    val nickname: String?,
)

enum class ConnectionState { HEALTHY, FAILED }

enum class ConnectionFailureCode {
    AUTHENTICATION,
    AUTHORIZATION,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK,
    UPSTREAM,
    UNEXPECTED,
}

data class ConnectionFailure(
    val code: ConnectionFailureCode,
    val message: String,
)

data class BitbucketConnectionSnapshot(
    val state: ConnectionState,
    val account: BitbucketAccount?,
    val lastAttemptAt: Instant,
    val lastSuccessAt: Instant?,
    val failure: ConnectionFailure?,
)

sealed interface BitbucketAccountResult {
    data class Success(val account: BitbucketAccount) : BitbucketAccountResult
    data class Failure(val failure: ConnectionFailure) : BitbucketAccountResult
}
