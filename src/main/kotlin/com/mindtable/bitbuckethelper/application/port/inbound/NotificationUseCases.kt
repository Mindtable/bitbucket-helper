package com.mindtable.bitbuckethelper.application.port.inbound

import com.mindtable.bitbuckethelper.application.model.NotificationDispatchSummary
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId

fun interface DispatchNotifications {
    suspend operator fun invoke(intentIds: List<NotificationIntentId>): NotificationDispatchSummary
}

fun interface RetryPendingNotifications {
    suspend operator fun invoke(): NotificationDispatchSummary
}

fun interface SendDueReminders {
    suspend operator fun invoke(): List<NotificationIntentId>
}

fun interface SendInitialRepositoryDigest {
    suspend operator fun invoke(repositoryId: RepositoryId): List<NotificationIntentId>
}
