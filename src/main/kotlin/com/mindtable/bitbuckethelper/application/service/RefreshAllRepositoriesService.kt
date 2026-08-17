package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.RefreshAllRepositoriesResult
import com.mindtable.bitbuckethelper.application.model.RefreshRepositoryCommand
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshAllRepositories
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshRepository
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class RefreshAllRepositoriesService(
    private val transactions: ApplicationTransactionRunner,
    private val refreshRepository: RefreshRepository,
    maximumConcurrency: Int,
) : RefreshAllRepositories {
    private val semaphore = Semaphore(maximumConcurrency.also {
        require(it > 0) { "Refresh-all concurrency must be positive" }
    })

    override suspend fun invoke(): RefreshAllRepositoriesResult {
        val repositoryIds = transactions.inTransaction {
            configurationStore.find()?.repositories.orEmpty()
                .filter { it.removedAt == null }
                .map { it.id }
        }
        val results = coroutineScope {
            repositoryIds.map { repositoryId ->
                async {
                    semaphore.withPermit {
                        refreshRepository(RefreshRepositoryCommand(repositoryId))
                    }
                }
            }.awaitAll()
        }
        return RefreshAllRepositoriesResult(results)
    }
}
