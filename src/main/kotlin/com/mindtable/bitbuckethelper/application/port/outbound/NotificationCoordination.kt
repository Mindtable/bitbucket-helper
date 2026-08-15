package com.mindtable.bitbuckethelper.application.port.outbound

import com.mindtable.bitbuckethelper.application.model.NewNotificationIntent
import com.mindtable.bitbuckethelper.application.model.NotificationTransitionFact
import com.mindtable.bitbuckethelper.application.model.ReminderNotificationFact
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId

interface NotificationIntentPolicy {
    fun createIntents(facts: List<NotificationTransitionFact>): List<NewNotificationIntent>
    fun createReminder(fact: ReminderNotificationFact): NewNotificationIntent
}

fun interface PostCommitNotificationDispatcher {
    suspend fun dispatchCommitted(intentIds: List<NotificationIntentId>)
}
