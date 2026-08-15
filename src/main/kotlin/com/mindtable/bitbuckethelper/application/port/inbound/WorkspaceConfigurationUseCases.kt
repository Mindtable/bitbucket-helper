package com.mindtable.bitbuckethelper.application.port.inbound

import com.mindtable.bitbuckethelper.application.model.AddRepositoryCommand
import com.mindtable.bitbuckethelper.application.model.AddRepositoryResult
import com.mindtable.bitbuckethelper.application.model.ConfigureWorkspaceCommand
import com.mindtable.bitbuckethelper.application.model.ConfigureWorkspaceResult
import com.mindtable.bitbuckethelper.application.model.GetWorkspaceConfigurationResult
import com.mindtable.bitbuckethelper.application.model.RemoveRepositoryCommand
import com.mindtable.bitbuckethelper.application.model.RemoveRepositoryResult

fun interface GetWorkspaceConfiguration {
    suspend operator fun invoke(): GetWorkspaceConfigurationResult
}

fun interface ConfigureWorkspace {
    suspend operator fun invoke(command: ConfigureWorkspaceCommand): ConfigureWorkspaceResult
}

fun interface AddRepository {
    suspend operator fun invoke(command: AddRepositoryCommand): AddRepositoryResult
}

fun interface RemoveRepository {
    suspend operator fun invoke(command: RemoveRepositoryCommand): RemoveRepositoryResult
}
