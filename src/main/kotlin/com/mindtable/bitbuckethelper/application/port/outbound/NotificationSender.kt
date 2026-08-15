package com.mindtable.bitbuckethelper.application.port.outbound

import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryResult
import com.mindtable.bitbuckethelper.application.model.NotificationRequest

fun interface NotificationSender {
    suspend fun send(request: NotificationRequest): NotificationDeliveryResult
}
