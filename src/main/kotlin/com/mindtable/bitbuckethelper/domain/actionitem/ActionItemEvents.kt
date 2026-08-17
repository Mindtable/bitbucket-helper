package com.mindtable.bitbuckethelper.domain.actionitem

import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion

sealed interface ActionItemEvent { val actionItemId: ActionItemId }

data class ActionItemOpened(override val actionItemId: ActionItemId, val activityVersion: ActivityVersion) : ActionItemEvent
data class ActionItemVersionAdvanced(override val actionItemId: ActionItemId, val previous: ActivityVersion, val current: ActivityVersion) : ActionItemEvent
data class ActionItemClosed(override val actionItemId: ActionItemId, val state: ActionObservationState) : ActionItemEvent
data class ActionItemReopened(override val actionItemId: ActionItemId, val activityVersion: ActivityVersion) : ActionItemEvent
