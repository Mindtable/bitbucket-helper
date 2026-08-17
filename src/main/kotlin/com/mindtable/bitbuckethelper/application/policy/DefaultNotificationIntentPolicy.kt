package com.mindtable.bitbuckethelper.application.policy

import com.mindtable.bitbuckethelper.application.model.NewNotificationIntent
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryKey
import com.mindtable.bitbuckethelper.application.model.NotificationRequest
import com.mindtable.bitbuckethelper.application.model.NotificationSound
import com.mindtable.bitbuckethelper.application.model.NotificationTransitionFact
import com.mindtable.bitbuckethelper.application.model.ReminderNotificationFact
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationIntentPolicy
import java.net.URI
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class DefaultNotificationIntentPolicy : NotificationIntentPolicy {
    override fun createIntents(facts: List<NotificationTransitionFact>): List<NewNotificationIntent> = facts
        .map(::createIntent)
        .sortedBy { it.request.deliveryKey.value }

    override fun createReminder(fact: ReminderNotificationFact): NewNotificationIntent = NewNotificationIntent(
        request = NotificationRequest(
            deliveryKey = NotificationDeliveryKey(
                "reminder:${fact.repositoryId.value}:${REMINDER_HOUR_FORMAT.format(fact.utcHour)}",
            ),
            title = "Bitbucket Helper reminder",
            body = "${fact.repositoryDisplayName}: ${fact.actionableItemCount} ${itemLabel(fact.actionableItemCount)} still need attention",
            openUrl = safeHttpsUrl(fact.repositoryWebUrl),
            sound = NotificationSound.DEFAULT,
        ),
        createdAt = fact.utcHour,
    )

    private fun createIntent(fact: NotificationTransitionFact): NewNotificationIntent = when (fact) {
        is NotificationTransitionFact.InitialRepositoryDigest -> NewNotificationIntent(
            request = NotificationRequest(
                deliveryKey = NotificationDeliveryKey("initial-digest:${fact.repositoryId.value}"),
                title = "Bitbucket Helper",
                body = "${fact.repositoryDisplayName}: ${fact.actionableItemCount} ${itemLabel(fact.actionableItemCount)} need attention",
                openUrl = safeHttpsUrl(fact.repositoryWebUrl),
                sound = NotificationSound.DEFAULT,
            ),
            createdAt = fact.createdAt,
        )

        is NotificationTransitionFact.ActionableActivity -> NewNotificationIntent(
            request = NotificationRequest(
                deliveryKey = NotificationDeliveryKey(
                    "actionable:${fact.actionItemId.value}:${fact.activityVersion.value}",
                ),
                title = "Bitbucket Helper",
                body = "${fact.repositoryDisplayName}: PR #${fact.pullRequestNumber} needs attention",
                openUrl = safeHttpsUrl(fact.pullRequestWebUrl),
                sound = NotificationSound.DEFAULT,
            ),
            createdAt = fact.createdAt,
        )

        is NotificationTransitionFact.BuildsBecameGreen -> NewNotificationIntent(
            request = NotificationRequest(
                deliveryKey = NotificationDeliveryKey("builds-green:${fact.transitionId.value}"),
                title = "Bitbucket Helper",
                body = "${fact.repositoryDisplayName}: PR #${fact.pullRequestNumber} builds are green",
                openUrl = safeHttpsUrl(fact.pullRequestWebUrl),
                sound = NotificationSound.DEFAULT,
            ),
            createdAt = fact.createdAt,
        )
    }

    private fun safeHttpsUrl(url: URI): URI? = url.takeIf {
        it.scheme.equals("https", ignoreCase = true) && it.host?.isNotBlank() == true && it.userInfo == null
    }

    private fun itemLabel(count: Int): String = if (count == 1) "item" else "items"

    private companion object {
        val REMINDER_HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HH'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
