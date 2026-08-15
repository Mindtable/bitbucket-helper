package com.mindtable.bitbuckethelper.domain.pullrequest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadinessTest {
    @Test
    fun `assesses the seven checks in their stable order with a fixed denominator`() {
        val assessment = SevenCheckReadiness.assess(
            approvalCount = 1,
            unresolvedTaskCount = 0,
            unresolvedCommentCount = 0,
            destinationBranchIsCurrent = true,
            hasMergeConflicts = false,
            builds = listOf(BuildStatus.SUCCESSFUL),
            effectiveDefaultReviewerIds = setOf("reviewer-1"),
            approvedReviewerIds = setOf("reviewer-1"),
        )

        assertEquals(7, assessment.total)
        assertEquals(7, assessment.passedCount)
        assertEquals(
            listOf(
                ReadinessCheckName.APPROVALS,
                ReadinessCheckName.UNRESOLVED_TASKS,
                ReadinessCheckName.UNRESOLVED_COMMENTS,
                ReadinessCheckName.BRANCH_FRESHNESS,
                ReadinessCheckName.MERGE_CONFLICTS,
                ReadinessCheckName.SUCCESSFUL_BUILDS,
                ReadinessCheckName.REQUIRED_REVIEWER_STATE,
            ),
            assessment.checks.map(ReadinessCheck::name),
        )
        assertEquals(listOf(null, null, null, null, null, null, null), assessment.checks.map(ReadinessCheck::safeReason))
    }

    @Test
    fun `fails each failed or unknown fact safely without changing the seven-check denominator`() {
        val assessment = SevenCheckReadiness.assess(
            approvalCount = 0,
            unresolvedTaskCount = 1,
            unresolvedCommentCount = 1,
            destinationBranchIsCurrent = false,
            hasMergeConflicts = true,
            builds = listOf(BuildStatus.SUCCESSFUL, BuildStatus.FAILED),
            effectiveDefaultReviewerIds = setOf("reviewer-1"),
            approvedReviewerIds = emptySet(),
        )

        assertEquals(0, assessment.passedCount)
        assertEquals(7, assessment.total)
        assertEquals(
            listOf(
                "No approval observed",
                "Unresolved tasks observed",
                "Unresolved comments observed",
                "Destination branch is not current",
                "Merge conflicts observed",
                "Builds are not all successful",
                "Required reviewer has not approved",
            ),
            assessment.checks.map(ReadinessCheck::safeReason),
        )
    }

    @Test
    fun `distinguishes no builds unknown builds numeric boundaries and null upstream facts`() {
        val noBuilds = SevenCheckReadiness.assess(
            approvalCount = 1,
            unresolvedTaskCount = 0,
            unresolvedCommentCount = 0,
            destinationBranchIsCurrent = true,
            hasMergeConflicts = false,
            builds = emptyList(),
            effectiveDefaultReviewerIds = emptySet(),
            approvedReviewerIds = emptySet(),
        )
        val unknownBuild = SevenCheckReadiness.assess(
            approvalCount = 1,
            unresolvedTaskCount = 0,
            unresolvedCommentCount = 0,
            destinationBranchIsCurrent = true,
            hasMergeConflicts = false,
            builds = listOf(BuildStatus.UNKNOWN),
            effectiveDefaultReviewerIds = emptySet(),
            approvedReviewerIds = emptySet(),
        )
        val unknownFacts = SevenCheckReadiness.assess(
            approvalCount = null,
            unresolvedTaskCount = -1,
            unresolvedCommentCount = null,
            destinationBranchIsCurrent = null,
            hasMergeConflicts = null,
            builds = null,
            effectiveDefaultReviewerIds = null,
            approvedReviewerIds = null,
        )

        assertEquals("No builds observed", noBuilds.checks[5].safeReason)
        assertEquals("Unknown build status observed", unknownBuild.checks[5].safeReason)
        assertEquals(
            listOf(
                "Approval count unavailable",
                "Unresolved task count is invalid",
                "Unresolved comment count unavailable",
                "Destination branch freshness unavailable",
                "Merge conflict state unavailable",
                "Build status unavailable",
                "Required reviewer state unavailable",
            ),
            unknownFacts.checks.map(ReadinessCheck::safeReason),
        )
    }
}
