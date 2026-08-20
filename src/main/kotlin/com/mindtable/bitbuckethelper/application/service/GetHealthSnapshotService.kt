package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.HealthComponent
import com.mindtable.bitbuckethelper.application.model.HealthComponentSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthStatus
import com.mindtable.bitbuckethelper.application.port.inbound.GetHealthSnapshot
import com.mindtable.bitbuckethelper.application.port.outbound.HealthComponentProbe
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEvent
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEventRecorder
import java.time.Instant
import kotlinx.coroutines.CancellationException

class GetHealthSnapshotService(
    private val serviceVersion: String,
    private val supportedApiVersion: String,
    private val serviceInstanceId: String,
    private val startedAt: Instant,
    probes: List<HealthComponentProbe>,
    private val operationalEventRecorder: OperationalEventRecorder = OperationalEventRecorder.NONE,
) : GetHealthSnapshot {
    private val probesByComponent = probes.associateBy(HealthComponentProbe::component)

    init {
        require(
            probes.size == HealthComponent.entries.size &&
                probesByComponent.keys == HealthComponent.entries.toSet(),
        ) { "Exactly one probe is required for every health component" }
    }

    override suspend fun invoke(): HealthSnapshot {
        val components = HealthComponent.entries.map { component ->
            val probe = probesByComponent.getValue(component)
            try {
                probe.probe().takeIf { it.component == component }
                    ?: HealthComponentSnapshot(component, HealthStatus.UNHEALTHY, PROBE_FAILED_CODE)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                try {
                    operationalEventRecorder.record(OperationalEvent.HealthProbeFailed(component, failure))
                } catch (_: Throwable) {
                    // A recorder failure must not alter the safe health result.
                }
                HealthComponentSnapshot(component, HealthStatus.UNHEALTHY, PROBE_FAILED_CODE)
            }
        }
        return HealthSnapshot(
            status = components.aggregateStatus(),
            serviceVersion = serviceVersion,
            supportedApiVersion = supportedApiVersion,
            serviceInstanceId = serviceInstanceId,
            startedAt = startedAt,
            components = components,
        )
    }

    private companion object {
        const val PROBE_FAILED_CODE = "probe_failed"
    }
}

private fun List<HealthComponentSnapshot>.aggregateStatus(): HealthStatus = when {
    any { it.status == HealthStatus.UNHEALTHY } -> HealthStatus.UNHEALTHY
    any { it.status == HealthStatus.DEGRADED } -> HealthStatus.DEGRADED
    else -> HealthStatus.HEALTHY
}
