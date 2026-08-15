package com.mindtable.bitbuckethelper.application.port.inbound

import com.mindtable.bitbuckethelper.application.model.GetRefreshRunResult
import com.mindtable.bitbuckethelper.application.model.StartRefreshRunCommand
import com.mindtable.bitbuckethelper.application.model.StartRefreshRunResult
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId

fun interface StartRefreshRun {
    suspend operator fun invoke(command: StartRefreshRunCommand): StartRefreshRunResult
}

fun interface GetRefreshRun {
    suspend operator fun invoke(refreshRunId: RefreshRunId): GetRefreshRunResult
}
