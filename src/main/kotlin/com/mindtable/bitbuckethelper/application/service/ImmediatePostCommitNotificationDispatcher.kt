package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.port.inbound.DispatchNotifications
import com.mindtable.bitbuckethelper.application.port.outbound.PostCommitNotificationDispatcher
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId

class ImmediatePostCommitNotificationDispatcher(
    private val dispatchNotifications: DispatchNotifications,
) : PostCommitNotificationDispatcher {
    override suspend fun dispatchCommitted(intentIds: List<NotificationIntentId>) {
        dispatchNotifications(intentIds)
    }
}
