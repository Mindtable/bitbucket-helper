package com.mindtable.bitbuckethelper.adapter.outbound.persistence.memory

import com.mindtable.bitbuckethelper.application.contract.ApplicationPersistenceContract
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner

class InMemoryApplicationPersistenceTest : ApplicationPersistenceContract() {
    override fun createPersistence(): ApplicationTransactionRunner = InMemoryApplicationPersistence()
}
