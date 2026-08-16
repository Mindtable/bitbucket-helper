package com.mindtable.bitbuckethelper.cli

/** Stable process exits for the product command surface. */
enum class CliExit(val code: Int) {
    SUCCESS(0),
    UNEXPECTED_FAILURE(1),
    USAGE_ERROR(2),
    BUSINESS_NOT_ACHIEVED(3),
    SERVICE_OR_PROTOCOL_FAILURE(4),
}
