package com.mindtable.bitbuckethelper.application.port.inbound

import com.mindtable.bitbuckethelper.application.model.RefreshAllRepositoriesResult
import com.mindtable.bitbuckethelper.application.model.RefreshRepositoryCommand
import com.mindtable.bitbuckethelper.application.model.RefreshRepositoryResult

fun interface RefreshRepository {
    suspend operator fun invoke(command: RefreshRepositoryCommand): RefreshRepositoryResult
}

fun interface RefreshAllRepositories {
    suspend operator fun invoke(): RefreshAllRepositoriesResult
}
