package com.mindtable.bitbuckethelper.application.port.inbound

import com.mindtable.bitbuckethelper.application.model.HealthSnapshot

fun interface GetHealthSnapshot {
    suspend operator fun invoke(): HealthSnapshot
}
