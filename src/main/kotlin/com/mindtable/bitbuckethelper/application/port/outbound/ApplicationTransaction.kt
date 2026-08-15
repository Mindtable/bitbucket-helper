package com.mindtable.bitbuckethelper.application.port.outbound

interface ApplicationTransactionRunner {
    suspend fun <T> inTransaction(block: suspend ApplicationTransaction.() -> T): T
}

interface ApplicationTransaction {
    val configurationStore: ConfigurationStore
    val pullRequestStore: PullRequestStore
    val actionItemStore: ActionItemStore
    val synchronizationCheckpointStore: SynchronizationCheckpointStore
    val notificationIntentStore: NotificationIntentStore
    val reminderProjectionStore: ReminderProjectionStore
}
