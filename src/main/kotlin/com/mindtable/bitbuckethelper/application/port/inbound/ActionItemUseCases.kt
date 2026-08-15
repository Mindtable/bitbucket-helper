package com.mindtable.bitbuckethelper.application.port.inbound

import com.mindtable.bitbuckethelper.application.model.AcknowledgeActionItemCommand
import com.mindtable.bitbuckethelper.application.model.AcknowledgeActionItemResult
import com.mindtable.bitbuckethelper.application.model.GetLiveActivityContentCommand
import com.mindtable.bitbuckethelper.application.model.LiveActivityContentResult

fun interface GetLiveActivityContent {
    suspend operator fun invoke(command: GetLiveActivityContentCommand): LiveActivityContentResult
}

fun interface AcknowledgeActionItem {
    suspend operator fun invoke(command: AcknowledgeActionItemCommand): AcknowledgeActionItemResult
}
