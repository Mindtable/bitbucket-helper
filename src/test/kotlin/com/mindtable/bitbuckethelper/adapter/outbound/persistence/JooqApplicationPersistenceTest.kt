package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import com.mindtable.bitbuckethelper.application.contract.ApplicationPersistenceContract
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import java.nio.file.Files

class JooqApplicationPersistenceTest : ApplicationPersistenceContract() {
    override fun createPersistence(): ApplicationTransactionRunner {
        val path = Files.createTempDirectory("jooq-application-persistence").resolve("state.sqlite")
        return JooqApplicationPersistence.open(path)
    }
}
