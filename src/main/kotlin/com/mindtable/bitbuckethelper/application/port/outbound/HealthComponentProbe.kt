package com.mindtable.bitbuckethelper.application.port.outbound

import com.mindtable.bitbuckethelper.application.model.HealthComponent
import com.mindtable.bitbuckethelper.application.model.HealthComponentSnapshot

interface HealthComponentProbe {
    val component: HealthComponent
    suspend fun probe(): HealthComponentSnapshot
}
