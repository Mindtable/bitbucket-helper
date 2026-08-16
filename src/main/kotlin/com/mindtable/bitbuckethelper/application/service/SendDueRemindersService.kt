package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.NotificationIntentInsertResult
import com.mindtable.bitbuckethelper.application.model.NotificationIntentState
import com.mindtable.bitbuckethelper.application.model.ReminderNotificationFact
import com.mindtable.bitbuckethelper.application.model.StoredNotificationIntent
import com.mindtable.bitbuckethelper.application.port.inbound.SendDueReminders
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationIntentPolicy
import com.mindtable.bitbuckethelper.application.port.outbound.PostCommitNotificationDispatcher
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat

class SendDueRemindersService(
    private val transactions: ApplicationTransactionRunner,
    private val clock: Clock,
    private val intentPolicy: NotificationIntentPolicy,
    private val postCommitDispatcher: PostCommitNotificationDispatcher,
) : SendDueReminders {
    override suspend fun invoke(): List<NotificationIntentId> {
        val utcHour = clock.instant().truncatedTo(ChronoUnit.HOURS)
        val insertedIds = transactions.inTransaction {
            val inserted = mutableListOf<NotificationIntentId>()
            val repositories = reminderProjectionStore.listRepositoriesWithActionableItems()
                .sortedWith(
                    compareBy(
                        { it.repositoryId.value },
                        { it.displayName },
                        { it.webUrl.toString() },
                    ),
                )
                .distinctBy { it.repositoryId }
            for (repository in repositories) {
                val actionableItems = reminderProjectionStore.listActionableItems(repository.repositoryId)
                    .distinctBy { it.actionItemId }
                if (actionableItems.isEmpty()) continue
                val draft = intentPolicy.createReminder(
                    ReminderNotificationFact(
                        repositoryId = repository.repositoryId,
                        repositoryDisplayName = repository.displayName,
                        repositoryWebUrl = repository.webUrl,
                        actionableItemCount = actionableItems.size,
                        utcHour = utcHour,
                    ),
                )
                val stored = StoredNotificationIntent(
                    id = notificationIntentId(draft.request.deliveryKey.value),
                    request = draft.request,
                    createdAt = draft.createdAt,
                    state = NotificationIntentState.PENDING,
                    attemptCount = 0,
                    nextAttemptAt = draft.createdAt,
                    lease = null,
                )
                when (val result = notificationIntentStore.insertIfAbsent(stored)) {
                    is NotificationIntentInsertResult.Inserted -> inserted += result.intent.id
                    is NotificationIntentInsertResult.Existing -> check(
                        result.intent.request.deliveryKey == stored.request.deliveryKey,
                    ) {
                        "Notification intent identity collision"
                    }
                }
            }
            inserted
        }
        if (insertedIds.isNotEmpty()) postCommitDispatcher.dispatchCommitted(insertedIds)
        return insertedIds
    }

    private fun notificationIntentId(deliveryKey: String): NotificationIntentId {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(deliveryKey.toByteArray(StandardCharsets.UTF_8))
        return NotificationIntentId("ni_${HexFormat.of().formatHex(digest)}")
    }
}
