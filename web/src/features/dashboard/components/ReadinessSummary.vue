<script setup lang="ts">
import { computed } from 'vue'

import type { PullRequestSummary, ReadinessCheckModel } from '../dashboard.models'
import BuildStatus from './BuildStatus.vue'

const props = defineProps<{
  pullRequest: PullRequestSummary
  readinessChecks: readonly ReadinessCheckModel[]
}>()

const readinessLabel = computed(() =>
  props.pullRequest.readiness.type === 'available'
    ? `${props.pullRequest.readiness.passed} of ${props.pullRequest.readiness.total} checks passed`
    : `Readiness unavailable: ${props.pullRequest.readiness.reason}`,
)
const actionableLabel = computed(
  () =>
    `${props.pullRequest.actionableItemCount} actionable item${props.pullRequest.actionableItemCount === 1 ? '' : 's'}`,
)
const acknowledgedLabel = computed(
  () =>
    `${props.pullRequest.acknowledgedItemCount} acknowledged item${props.pullRequest.acknowledgedItemCount === 1 ? '' : 's'}`,
)

const checkStateLabel: Record<ReadinessCheckModel['state'], string> = {
  passed: 'Passed',
  pending: 'Pending',
  failed: 'Failed',
  unavailable: 'Unavailable',
}
</script>

<template>
  <section class="readiness-summary" aria-labelledby="drawer-readiness-heading">
    <h3 id="drawer-readiness-heading">Readiness</h3>
    <p>{{ readinessLabel }}</p>
    <p>
      <BuildStatus
        :build-state="pullRequest.buildState"
        :control-id="`drawer-${pullRequest.pullRequestId}`"
      />
    </p>
    <ul v-if="readinessChecks.length > 0" class="readiness-checks">
      <li v-for="check in readinessChecks" :key="check.checkId">
        <span>{{ check.label }}</span>
        <span :data-readiness-state="check.state">{{ checkStateLabel[check.state] }}</span>
      </li>
    </ul>
    <p v-else class="empty-state">Detailed readiness checks are unavailable.</p>
    <p class="readiness-counts">{{ actionableLabel }} · {{ acknowledgedLabel }}</p>
  </section>
</template>
