<script setup lang="ts">
import { computed } from 'vue'
import type { PullRequestSummary } from '../dashboard.models'

const props = defineProps<{ pullRequest: PullRequestSummary }>()
const readinessLabel = computed(() => props.pullRequest.readiness.type === 'available' ? props.pullRequest.readiness.passed + ' of ' + props.pullRequest.readiness.total + ' checks' : 'Readiness unavailable: ' + props.pullRequest.readiness.reason)
const buildLabel = computed(() => {
  switch (props.pullRequest.buildState.type) {
    case 'successful': return 'Build successful'
    case 'failed': return 'Build failed'
    case 'inProgress': return 'Build in progress'
    case 'unavailable': return 'Build unavailable: ' + props.pullRequest.buildState.reason
  }
  return ''
})
const actionItemLabel = computed(() => props.pullRequest.actionableItemCount + ' actionable item' + (props.pullRequest.actionableItemCount === 1 ? '' : 's'))
</script>

<template>
  <article class="pull-request-card"><header><p class="pull-request-number">#{{ pullRequest.displayNumber }}</p><h4><a :href="pullRequest.webUrl" target="_blank" rel="noopener noreferrer">{{ pullRequest.title }}</a></h4><p class="pull-request-meta">By {{ pullRequest.authorDisplayName }} · Updated <time :datetime="pullRequest.updatedAt">{{ pullRequest.updatedAt }}</time></p></header><ul class="pull-request-status"><li>{{ readinessLabel }}</li><li>{{ buildLabel }}</li><li>{{ actionItemLabel }}</li></ul></article>
</template>
