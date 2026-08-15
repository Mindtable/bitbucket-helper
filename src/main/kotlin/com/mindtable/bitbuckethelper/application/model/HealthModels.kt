package com.mindtable.bitbuckethelper.application.model

import java.time.Instant

enum class HealthStatus { HEALTHY, DEGRADED, UNHEALTHY }

enum class HealthComponent {
    PERSISTENCE,
    SCHEDULER,
    INSTALLATION_PATH,
    NOTIFICATION_ADAPTER,
}

data class HealthComponentSnapshot(
    val component: HealthComponent,
    val status: HealthStatus,
    val safeCode: String,
)

data class HealthSnapshot(
    val status: HealthStatus,
    val serviceVersion: String,
    val supportedApiVersion: String,
    val serviceInstanceId: String,
    val startedAt: Instant,
    val components: List<HealthComponentSnapshot>,
)
