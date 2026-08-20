package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.HealthComponent as ApplicationHealthComponent
import com.mindtable.bitbuckethelper.application.model.HealthComponentSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthStatus as ApplicationHealthStatus
import com.mindtable.bitbuckethelper.application.port.inbound.GetHealthSnapshot
import com.mindtable.bitbuckethelper.generated.api.v1.model.ApiVersion
import com.mindtable.bitbuckethelper.generated.api.v1.model.HealthComponent
import com.mindtable.bitbuckethelper.generated.api.v1.model.HealthComponentName
import com.mindtable.bitbuckethelper.generated.api.v1.model.HealthResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.HealthSnapshotResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.HealthStatus
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.installHealthRoutes(getHealthSnapshot: GetHealthSnapshot) {
    get("/health") {
        call.observeApiOperation(ApiOperation.HEALTH)
        val snapshot = getHealthSnapshot()
        call.respondApiV1(snapshot.toApiV1Outcome()) { requestId -> snapshot.toHealthResponse(requestId) }
    }
}

private fun HealthSnapshot.toHealthResponse(requestId: String) = HealthResponse(
    apiVersion = ApiVersion._1,
    requestId = requestId,
    result = HealthSnapshotResult(
        type = HealthSnapshotResult.Type.healthSnapshot,
        status = status.toGenerated(),
        serviceVersion = serviceVersion,
        supportedApiVersion = supportedApiVersion.toGeneratedApiVersion(),
        serviceInstanceId = serviceInstanceId,
        startedAt = startedAt.toString(),
        components = components.map(HealthComponentSnapshot::toGenerated),
    ),
)

private fun HealthComponentSnapshot.toGenerated() = HealthComponent(
    component = component.toGenerated(),
    status = status.toGenerated(),
    safeCode = safeCode,
)

private fun ApplicationHealthStatus.toGenerated(): HealthStatus = when (this) {
    ApplicationHealthStatus.HEALTHY -> HealthStatus.healthy
    ApplicationHealthStatus.DEGRADED -> HealthStatus.degraded
    ApplicationHealthStatus.UNHEALTHY -> HealthStatus.unhealthy
}

private fun ApplicationHealthComponent.toGenerated(): HealthComponentName = when (this) {
    ApplicationHealthComponent.PERSISTENCE -> HealthComponentName.persistence
    ApplicationHealthComponent.SCHEDULER -> HealthComponentName.scheduler
    ApplicationHealthComponent.INSTALLATION_PATH -> HealthComponentName.installationPath
    ApplicationHealthComponent.NOTIFICATION_ADAPTER -> HealthComponentName.notificationAdapter
}

private fun String.toGeneratedApiVersion(): ApiVersion = when (this) {
    "1" -> ApiVersion._1
    else -> error("The health snapshot declared an unsupported API version")
}
