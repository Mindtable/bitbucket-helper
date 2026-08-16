package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.NotificationDispatchSummary
import com.mindtable.bitbuckethelper.application.port.inbound.DispatchNotifications
import com.mindtable.bitbuckethelper.application.port.inbound.RetryPendingNotifications
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import java.time.Clock

class RetryPendingNotificationsService(
    private val transactions: ApplicationTransactionRunner,
    private val dispatchNotifications: DispatchNotifications,
    private val clock: Clock,
) : RetryPendingNotifications {
    override suspend fun invoke(): NotificationDispatchSummary {
        val dueIntentIds = transactions.inTransaction {
            notificationIntentStore.findDue(clock.instant(), DEFAULT_BATCH_SIZE).map { it.id }
        }
        return dispatchNotifications(dueIntentIds)
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 100
    }
}
