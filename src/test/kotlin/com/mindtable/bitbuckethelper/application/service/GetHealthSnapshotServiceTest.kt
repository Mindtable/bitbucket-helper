package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.HealthComponent
import com.mindtable.bitbuckethelper.application.model.HealthComponentSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthStatus
import com.mindtable.bitbuckethelper.application.port.outbound.HealthComponentProbe
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GetHealthSnapshotServiceTest {
    @Test
    fun `all healthy required probes produce an ordered healthy snapshot with service identity`() = runTest {
        val service = service(
            listOf(
                probe(HealthComponent.NOTIFICATION_ADAPTER, HealthStatus.HEALTHY, "available"),
                probe(HealthComponent.INSTALLATION_PATH, HealthStatus.HEALTHY, "accessible"),
                probe(HealthComponent.SCHEDULER, HealthStatus.HEALTHY, "running"),
                probe(HealthComponent.PERSISTENCE, HealthStatus.HEALTHY, "ready"),
            ),
        )

        val snapshot = service()

        assertEquals(HealthStatus.HEALTHY, snapshot.status)
        assertEquals("1.0.0", snapshot.serviceVersion)
        assertEquals("v1", snapshot.supportedApiVersion)
        assertEquals("svc_acceptance", snapshot.serviceInstanceId)
        assertEquals(startedAt, snapshot.startedAt)
        assertEquals(HealthComponent.entries, snapshot.components.map { it.component })
        assertEquals(listOf("ready", "running", "accessible", "available"), snapshot.components.map { it.safeCode })
    }

    @Test
    fun `a degraded component degrades the service while healthy components remain visible`() = runTest {
        val service = service(
            HealthComponent.entries.map { component ->
                probe(
                    component,
                    if (component == HealthComponent.SCHEDULER) HealthStatus.DEGRADED else HealthStatus.HEALTHY,
                    if (component == HealthComponent.SCHEDULER) "delayed" else "ready",
                )
            },
        )

        val snapshot = service()

        assertEquals(HealthStatus.DEGRADED, snapshot.status)
        assertEquals(HealthStatus.DEGRADED, snapshot.components.single { it.component == HealthComponent.SCHEDULER }.status)
        assertEquals(3, snapshot.components.count { it.status == HealthStatus.HEALTHY })
    }

    @Test
    fun `an unhealthy component outranks degradation and probe failures expose only a safe code`() = runTest {
        val sensitiveFailure = "token=never-expose path=/Users/private raw upstream body"
        val probes = HealthComponent.entries.map { component ->
            when (component) {
                HealthComponent.PERSISTENCE -> probe(component, HealthStatus.DEGRADED, "busy")
                HealthComponent.INSTALLATION_PATH -> failingProbe(component, sensitiveFailure)
                else -> probe(component, HealthStatus.HEALTHY, "ready")
            }
        }

        val snapshot = service(probes)()

        assertEquals(HealthStatus.UNHEALTHY, snapshot.status)
        assertEquals(
            HealthComponentSnapshot(HealthComponent.INSTALLATION_PATH, HealthStatus.UNHEALTHY, "probe_failed"),
            snapshot.components.single { it.component == HealthComponent.INSTALLATION_PATH },
        )
        assertFalse(snapshot.toString().contains(sensitiveFailure))
        assertFalse(snapshot.toString().contains("/Users/private"))
    }

    @Test
    fun `construction rejects missing and duplicate required probes`() {
        val complete = HealthComponent.entries.map { probe(it, HealthStatus.HEALTHY, "ready") }

        assertThrows(IllegalArgumentException::class.java) { service(complete.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) { service(complete + complete.first()) }
    }

    private fun service(probes: List<HealthComponentProbe>) = GetHealthSnapshotService(
        serviceVersion = "1.0.0",
        supportedApiVersion = "v1",
        serviceInstanceId = "svc_acceptance",
        startedAt = startedAt,
        probes = probes,
    )

    private fun probe(
        component: HealthComponent,
        status: HealthStatus,
        safeCode: String,
    ) = object : HealthComponentProbe {
        override val component = component
        override suspend fun probe() = HealthComponentSnapshot(component, status, safeCode)
    }

    private fun failingProbe(component: HealthComponent, message: String) = object : HealthComponentProbe {
        override val component = component
        override suspend fun probe(): HealthComponentSnapshot = throw IllegalStateException(message)
    }

    private companion object {
        val startedAt: Instant = Instant.parse("2026-08-15T08:00:00Z")
    }
}
