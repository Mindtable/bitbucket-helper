<script setup lang="ts">
import { computed } from 'vue'

import type { DashboardRefreshState } from '../useDashboard'

export type ProductOverallStatus = 'idle' | 'active' | 'problem'

const props = defineProps<{
  workspaceDisplayName: string
  dashboardRevision: string
  overallStatus: ProductOverallStatus
  refreshState: DashboardRefreshState
}>()
const emit = defineEmits<{ refresh: [] }>()

function assertNever(state: never): never {
  throw new Error('Unexpected refresh state: ' + JSON.stringify(state))
}

const syncStatus = computed(() => {
  if (props.overallStatus === 'problem') return 'Sync needs attention'

  switch (props.refreshState.type) {
    case 'idle':
      return 'Sync idle'
    case 'registering':
      return 'Starting sync'
    case 'active':
      return 'Sync active'
    case 'failed':
      return props.refreshState.message
  }
  return assertNever(props.refreshState)
})
</script>

<template>
  <header class="product-header" :data-overall-status="overallStatus">
    <div>
      <p class="eyebrow">Product dashboard</p>
      <h1>Bitbucket Helper</h1>
    </div>
    <div class="product-header__metadata">
      <p class="workspace-name">
        <span class="eyebrow">Workspace</span>
        <span id="workspace-heading">{{ workspaceDisplayName }}</span>
      </p>
      <p class="sync-status" role="status" aria-live="polite">{{ syncStatus }}</p>
      <p class="dashboard-revision">
        Revision <code>{{ dashboardRevision }}</code>
      </p>
    </div>
    <button
      type="button"
      aria-label="Refresh dashboard"
      :disabled="refreshState.type === 'registering'"
      @click="emit('refresh')"
    >
      Refresh
    </button>
  </header>
</template>
