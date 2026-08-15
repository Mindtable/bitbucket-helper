package com.mindtable.bitbuckethelper.domain.pullrequest

enum class BuildStatus { SUCCESSFUL, FAILED, STOPPED, IN_PROGRESS, UNKNOWN }

enum class ReadinessCheckName {
    APPROVALS,
    UNRESOLVED_TASKS,
    UNRESOLVED_COMMENTS,
    BRANCH_FRESHNESS,
    MERGE_CONFLICTS,
    SUCCESSFUL_BUILDS,
    REQUIRED_REVIEWER_STATE,
}

data class ReadinessCheck(
    val name: ReadinessCheckName,
    val passed: Boolean,
    val safeReason: String?,
)

data class ReadinessAssessment(val checks: List<ReadinessCheck>) {
    val passedCount: Int = checks.count(ReadinessCheck::passed)
    val total: Int = 7
}

object SevenCheckReadiness {
    fun assess(
        approvalCount: Int?,
        unresolvedTaskCount: Int?,
        unresolvedCommentCount: Int?,
        destinationBranchIsCurrent: Boolean?,
        hasMergeConflicts: Boolean?,
        builds: List<BuildStatus>?,
        effectiveDefaultReviewerIds: Set<String>?,
        approvedReviewerIds: Set<String>?,
    ): ReadinessAssessment = ReadinessAssessment(
        listOf(
            countCheck(ReadinessCheckName.APPROVALS, approvalCount, "Approval", "No approval observed"),
            zeroCountCheck(ReadinessCheckName.UNRESOLVED_TASKS, unresolvedTaskCount, "Unresolved task"),
            zeroCountCheck(ReadinessCheckName.UNRESOLVED_COMMENTS, unresolvedCommentCount, "Unresolved comment"),
            booleanCheck(
                ReadinessCheckName.BRANCH_FRESHNESS,
                destinationBranchIsCurrent,
                "Destination branch freshness unavailable",
                "Destination branch is not current",
            ),
            booleanCheck(
                ReadinessCheckName.MERGE_CONFLICTS,
                hasMergeConflicts?.not(),
                "Merge conflict state unavailable",
                "Merge conflicts observed",
            ),
            buildCheck(builds),
            reviewerCheck(effectiveDefaultReviewerIds, approvedReviewerIds),
        ),
    )

    private fun countCheck(
        name: ReadinessCheckName,
        count: Int?,
        factName: String,
        failureReason: String,
    ): ReadinessCheck = when {
        count == null -> failed(name, "$factName count unavailable")
        count < 0 -> failed(name, "$factName count is invalid")
        count == 0 -> failed(name, failureReason)
        else -> passed(name)
    }

    private fun zeroCountCheck(
        name: ReadinessCheckName,
        count: Int?,
        factName: String,
    ): ReadinessCheck = when {
        count == null -> failed(name, "$factName count unavailable")
        count < 0 -> failed(name, "$factName count is invalid")
        count == 0 -> passed(name)
        else -> failed(name, "${factName}s observed")
    }

    private fun booleanCheck(
        name: ReadinessCheckName,
        value: Boolean?,
        unavailableReason: String,
        failureReason: String,
    ): ReadinessCheck = when (value) {
        null -> failed(name, unavailableReason)
        true -> passed(name)
        false -> failed(name, failureReason)
    }

    private fun buildCheck(builds: List<BuildStatus>?): ReadinessCheck = when {
        builds == null -> failed(ReadinessCheckName.SUCCESSFUL_BUILDS, "Build status unavailable")
        builds.isEmpty() -> failed(ReadinessCheckName.SUCCESSFUL_BUILDS, "No builds observed")
        builds.any { it == BuildStatus.UNKNOWN } -> failed(ReadinessCheckName.SUCCESSFUL_BUILDS, "Unknown build status observed")
        builds.all { it == BuildStatus.SUCCESSFUL } -> passed(ReadinessCheckName.SUCCESSFUL_BUILDS)
        else -> failed(ReadinessCheckName.SUCCESSFUL_BUILDS, "Builds are not all successful")
    }

    private fun reviewerCheck(
        effectiveDefaultReviewerIds: Set<String>?,
        approvedReviewerIds: Set<String>?,
    ): ReadinessCheck = when {
        effectiveDefaultReviewerIds == null || approvedReviewerIds == null ->
            failed(ReadinessCheckName.REQUIRED_REVIEWER_STATE, "Required reviewer state unavailable")
        effectiveDefaultReviewerIds.all(approvedReviewerIds::contains) -> passed(ReadinessCheckName.REQUIRED_REVIEWER_STATE)
        else -> failed(ReadinessCheckName.REQUIRED_REVIEWER_STATE, "Required reviewer has not approved")
    }

    private fun passed(name: ReadinessCheckName) = ReadinessCheck(name, passed = true, safeReason = null)

    private fun failed(name: ReadinessCheckName, safeReason: String) =
        ReadinessCheck(name, passed = false, safeReason = safeReason)
}
