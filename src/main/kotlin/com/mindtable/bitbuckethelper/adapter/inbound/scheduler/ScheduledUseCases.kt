package com.mindtable.bitbuckethelper.adapter.inbound.scheduler

import com.mindtable.bitbuckethelper.application.port.inbound.PruneInactivePullRequests
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshAllRepositories
import com.mindtable.bitbuckethelper.application.port.inbound.RetryPendingNotifications
import com.mindtable.bitbuckethelper.application.port.inbound.SendDueReminders

class ScheduledUseCases(
    refreshAllRepositories: RefreshAllRepositories,
    retryPendingNotifications: RetryPendingNotifications,
    sendDueReminders: SendDueReminders,
    pruneInactivePullRequests: PruneInactivePullRequests,
) {
    private val operations: Map<String, suspend () -> Unit> = mapOf(
        REFRESH_ALL_REPOSITORIES to { refreshAllRepositories() },
        RETRY_PENDING_NOTIFICATIONS to { retryPendingNotifications() },
        SEND_DUE_REMINDERS to { sendDueReminders() },
        PRUNE_INACTIVE_PULL_REQUESTS to { pruneInactivePullRequests() },
    )

    fun operation(key: String): suspend () -> Unit =
        operations[key] ?: throw IllegalArgumentException("Unsupported scheduled use case")

    companion object {
        const val REFRESH_ALL_REPOSITORIES = "refresh-all-repositories"
        const val RETRY_PENDING_NOTIFICATIONS = "retry-pending-notifications"
        const val SEND_DUE_REMINDERS = "send-due-reminders"
        const val PRUNE_INACTIVE_PULL_REQUESTS = "prune-inactive-pull-requests"
    }
}
