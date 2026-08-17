package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.AllConfiguredRepositoriesTarget
import com.mindtable.bitbuckethelper.generated.api.v1.model.ApiVersion
import com.mindtable.bitbuckethelper.generated.api.v1.model.GetRefreshRunResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.NoRepositoriesConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshDeferredByBackoffDisposition
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshDeferredByBackoffRepository
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshFailedRepository
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshJoinedExistingDisposition
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshPartialFailureRepository
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRegistrationDisposition
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRepositoryNotConfiguredDisposition
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRun
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRunCompletedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRunInProgressResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRunRegisteredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRunUnavailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshSucceededRepository
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshStartedDisposition
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoriesTarget
import com.mindtable.bitbuckethelper.generated.api.v1.model.StartRefreshRunRequest
import com.mindtable.bitbuckethelper.generated.api.v1.model.StartRefreshRunResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceNotConfiguredResult
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException

/** Registers a refresh run and, unless requested otherwise, waits synchronously for its terminal result. */
class RefreshCommand(
    private val client: LocalApiClient,
    private val output: CliOutput,
    private val sleeper: Sleeper = CoroutineSleeper,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun refresh(
        repositoryIds: List<String>,
        noWait: Boolean,
        mode: OutputMode,
    ): CliExit = try {
        if (repositoryIds.any { !REPOSITORY_ID.matches(it) }) {
            CliExit.USAGE_ERROR
        } else {
            start(repositoryIds, noWait, mode)
        }
    } catch (_: IOException) {
        output.render(mode, CliOutcome.serviceUnavailable())
    } catch (_: LocalApiResponseTooLargeException) {
        output.render(mode, CliOutcome.serviceUnavailable())
    } catch (_: SerializationException) {
        output.render(mode, CliOutcome.serviceUnavailable())
    } finally {
        client.close()
    }

    private suspend fun start(
        repositoryIds: List<String>,
        noWait: Boolean,
        mode: OutputMode,
    ): CliExit {
        val response = client.post(
            REFRESH_RUNS_PATH,
            StartRefreshRunRequest(ApiVersion._1, refreshTarget(repositoryIds)),
            StartRefreshRunRequest.serializer(),
            StartRefreshRunResponse.serializer(),
        )
        if (response.status != HttpStatusCode.OK || response.error != null) {
            return output.render(mode, CliOutcome.serviceUnavailable())
        }

        return when (val result = response.value?.result) {
            is WorkspaceNotConfiguredResult -> output.render(
                mode,
                CliOutcome.api(response, CliExit.BUSINESS_NOT_ACHIEVED) {
                    "Workspace is not configured. Run '${result.setupCommand.toString().humanEscaped()}'."
                },
            )

            is NoRepositoriesConfiguredResult -> output.render(
                mode,
                CliOutcome.api(response, CliExit.BUSINESS_NOT_ACHIEVED) {
                    "No repositories are configured for refresh."
                },
            )

            is RefreshRunRegisteredResult -> {
                val refreshRunId = result.refreshRun.refreshRunId
                val registration = classifyRegistration(result.dispositions)
                if (!REFRESH_RUN_ID.matches(refreshRunId) || registration == null) {
                    output.render(mode, CliOutcome.serviceUnavailable())
                } else if (noWait) {
                    output.render(
                        mode,
                        CliOutcome.api(
                            response,
                            if (registration.achieved) CliExit.SUCCESS else CliExit.BUSINESS_NOT_ACHIEVED,
                        ) {
                            withRegistrationDetails(
                                "Refresh run $refreshRunId registered; not waiting for completion.",
                                registration,
                            )
                        },
                    )
                } else {
                    val expiresAt = result.refreshRun.expiresAtOrNull()
                        ?: return output.render(mode, CliOutcome.serviceUnavailable())
                    poll(result.refreshRun, expiresAt, response, registration, mode)
                }
            }

            null -> output.render(mode, CliOutcome.serviceUnavailable())
        }
    }

    private suspend fun poll(
        registeredRun: RefreshRun,
        registeredExpiry: Instant,
        registrationResponse: LocalApiResponse<StartRefreshRunResponse>,
        registration: RegistrationClassification,
        mode: OutputMode,
    ): CliExit {
        val refreshRunId = registeredRun.refreshRunId
        if (remainingMilliseconds(registeredExpiry) <= 0L) {
            return renderExpired(registrationResponse, refreshRunId, registration, mode)
        }

        while (true) {
            val response = client.get(
                "$REFRESH_RUNS_PATH/$refreshRunId",
                GetRefreshRunResponse.serializer(),
            )
            if (response.status != HttpStatusCode.OK || response.error != null) {
                return output.render(mode, CliOutcome.serviceUnavailable())
            }

            when (val result = response.value?.result) {
                is RefreshRunInProgressResult -> {
                    if (!result.refreshRun.matches(refreshRunId)) {
                        return output.render(mode, CliOutcome.serviceUnavailable())
                    }
                    val statusExpiry = result.refreshRun.expiresAtOrNull()
                        ?: return output.render(mode, CliOutcome.serviceUnavailable())
                    val effectiveExpiry = minOf(registeredExpiry, statusExpiry)
                    val remaining = remainingMilliseconds(effectiveExpiry)
                    val advisedDelay = result.polling.afterMilliseconds
                    if (remaining <= 0L) {
                        return renderExpired(response, refreshRunId, registration, mode)
                    }
                    if (advisedDelay <= 0L) {
                        return output.render(mode, CliOutcome.serviceUnavailable())
                    }

                    sleeper.sleep(minOf(advisedDelay, remaining))
                    if (advisedDelay >= remaining || remainingMilliseconds(effectiveExpiry) <= 0L) {
                        return renderExpired(response, refreshRunId, registration, mode)
                    }
                }

                is RefreshRunCompletedResult -> {
                    if (!result.refreshRun.matches(refreshRunId)) {
                        return output.render(mode, CliOutcome.serviceUnavailable())
                    }
                    return renderCompleted(response, result.refreshRun, registration, mode)
                }

                is RefreshRunUnavailableResult -> {
                    if (result.refreshRunId != refreshRunId || !REFRESH_RUN_ID.matches(result.refreshRunId)) {
                        return output.render(mode, CliOutcome.serviceUnavailable())
                    }
                    return output.render(
                        mode,
                        CliOutcome.api(response, CliExit.BUSINESS_NOT_ACHIEVED) {
                            withRegistrationDetails(
                                "Refresh run $refreshRunId is unavailable.",
                                registration,
                            )
                        },
                    )
                }

                null -> return output.render(mode, CliOutcome.serviceUnavailable())
            }
        }
    }

    private fun renderCompleted(
        response: LocalApiResponse<GetRefreshRunResponse>,
        refreshRun: RefreshRun,
        registration: RegistrationClassification,
        mode: OutputMode,
    ): CliExit {
        val completion = completion(refreshRun)
        if (completion == Completion.PROTOCOL_FAILURE) {
            return output.render(mode, CliOutcome.serviceUnavailable())
        }
        val exit = if (completion == Completion.SUCCESS && registration.achieved) {
            CliExit.SUCCESS
        } else {
            CliExit.BUSINESS_NOT_ACHIEVED
        }
        val message = when {
            completion == Completion.BUSINESS_NOT_ACHIEVED ->
                "Refresh run ${refreshRun.refreshRunId} completed with unsuccessful repositories."
            !registration.achieved ->
                "Refresh run ${refreshRun.refreshRunId} completed, but not all requested repositories were registered."
            else -> "Refresh run ${refreshRun.refreshRunId} completed."
        }
        return output.render(
            mode,
            CliOutcome.api(response, exit) { withRegistrationDetails(message, registration) },
        )
    }

    private fun <Response> renderExpired(
        response: LocalApiResponse<Response>,
        refreshRunId: String,
        registration: RegistrationClassification,
        mode: OutputMode,
    ): CliExit = output.render(
        mode,
        CliOutcome.api(response, CliExit.BUSINESS_NOT_ACHIEVED) {
            withRegistrationDetails("Refresh run $refreshRunId expired before completion.", registration)
        },
    )

    private fun classifyRegistration(
        dispositions: List<RefreshRegistrationDisposition>,
    ): RegistrationClassification? {
        if (dispositions.isEmpty()) return null
        var achieved = true
        val details = mutableListOf<String>()
        for (disposition in dispositions) {
            val repositoryId = when (disposition) {
                is RefreshStartedDisposition -> disposition.repositoryId
                is RefreshJoinedExistingDisposition -> disposition.repositoryId
                is RefreshDeferredByBackoffDisposition -> disposition.repositoryId
                is RefreshRepositoryNotConfiguredDisposition -> disposition.repositoryId
            }
            if (!REPOSITORY_ID.matches(repositoryId)) return null
            val safeRepositoryId = repositoryId.humanEscaped()
            val detail = when (disposition) {
                is RefreshStartedDisposition -> "$safeRepositoryId: started"
                is RefreshJoinedExistingDisposition -> "$safeRepositoryId: joined existing"
                is RefreshDeferredByBackoffDisposition -> {
                    achieved = false
                    "$safeRepositoryId: deferred by backoff until ${disposition.retryAt.humanEscaped()}"
                }
                is RefreshRepositoryNotConfiguredDisposition -> {
                    achieved = false
                    "$safeRepositoryId: repository not configured"
                }
            }
            details += detail
        }
        return RegistrationClassification(achieved, details)
    }

    private fun withRegistrationDetails(
        message: String,
        registration: RegistrationClassification,
    ): String = buildString {
        appendLine(message)
        appendLine("Registration dispositions:")
        registration.details.forEach { appendLine("  $it") }
    }.trimEnd()

    private fun refreshTarget(repositoryIds: List<String>) = if (repositoryIds.isEmpty()) {
        AllConfiguredRepositoriesTarget()
    } else {
        RepositoriesTarget(LinkedHashSet(repositoryIds))
    }

    private fun remainingMilliseconds(expiresAt: Instant): Long = try {
        val remaining = Duration.between(clock.instant(), expiresAt)
        if (remaining.isZero || remaining.isNegative) {
            0L
        } else {
            remaining.toMillis().coerceAtLeast(1L)
        }
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private fun RefreshRun.expiresAtOrNull(): Instant? = try {
        Instant.parse(expiresAt)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun RefreshRun.matches(expectedRefreshRunId: String): Boolean =
        refreshRunId == expectedRefreshRunId && REFRESH_RUN_ID.matches(refreshRunId)

    private fun completion(refreshRun: RefreshRun): Completion = when {
        refreshRun.repositories.isEmpty() -> Completion.PROTOCOL_FAILURE
        refreshRun.repositories.all { it is RefreshSucceededRepository } -> Completion.SUCCESS
        refreshRun.repositories.all {
            it is RefreshSucceededRepository ||
                it is RefreshPartialFailureRepository ||
                it is RefreshFailedRepository ||
                it is RefreshDeferredByBackoffRepository
        } -> Completion.BUSINESS_NOT_ACHIEVED

        else -> Completion.PROTOCOL_FAILURE
    }

    private enum class Completion {
        SUCCESS,
        BUSINESS_NOT_ACHIEVED,
        PROTOCOL_FAILURE,
    }

    private data class RegistrationClassification(
        val achieved: Boolean,
        val details: List<String>,
    )

    private companion object {
        const val REFRESH_RUNS_PATH = "/api/v1/refresh-runs"
        val REPOSITORY_ID = Regex("^repo_[A-Za-z0-9_-]+$")
        val REFRESH_RUN_ID = Regex("^rr_[A-Za-z0-9_-]+$")
    }
}
