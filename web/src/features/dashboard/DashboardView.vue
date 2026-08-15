<script setup lang="ts">
import { computed, onUnmounted } from 'vue'

import ProductHeader, { type ProductOverallStatus } from './components/ProductHeader.vue'
import RepositoryGroup from './components/RepositoryGroup.vue'
import type { DashboardSource } from './dashboardSource'
import { useDashboard } from './useDashboard'

const props = defineProps<{ source: DashboardSource }>()
const { dispose, refresh, reload, state } = useDashboard(props.source)

onUnmounted(dispose)

const overallStatus = computed<ProductOverallStatus>(() => {
  if (state.value.type !== 'ready') return 'idle'
  if (
    state.value.dashboard.repositoryGroups.some(
      (repository) => repository.problem.type === 'present',
    )
  ) {
    return 'problem'
  }
  if (
    state.value.refresh.type === 'registering' ||
    state.value.refresh.type === 'active' ||
    state.value.dashboard.polling.type === 'active'
  ) {
    return 'active'
  }
  return 'idle'
})
</script>

<template>
  <main class="dashboard-shell">
    <p v-if="state.type === 'loading'" class="state-panel" role="status" aria-live="polite">
      Loading dashboard…
    </p>
    <section
      v-else-if="state.type === 'ready'"
      class="dashboard-content"
      aria-labelledby="workspace-heading"
    >
      <ProductHeader
        :workspace-display-name="state.dashboard.workspaceDisplayName"
        :dashboard-revision="state.dashboard.dashboardRevision"
        :overall-status="overallStatus"
        :refresh-state="state.refresh"
        @refresh="refresh"
      />
      <div class="repository-list">
        <RepositoryGroup
          v-for="repository in state.dashboard.repositoryGroups"
          :key="repository.repositoryId"
          :repository="repository"
        />
      </div>
    </section>
    <section
      v-else-if="state.type === 'workspaceNotConfigured'"
      class="state-panel"
      aria-labelledby="workspace-setup-heading"
    >
      <h2 id="workspace-setup-heading">Workspace not configured</h2>
      <p>Configure the workspace from the product CLI:</p>
      <code>{{ state.setupCommand }}</code>
    </section>
    <section v-else class="state-panel" role="alert" aria-labelledby="failure-heading">
      <h2 id="failure-heading">Dashboard unavailable</h2>
      <p>Bitbucket Helper could not load the dashboard.</p>
      <button type="button" @click="reload">Try again</button>
    </section>
  </main>
</template>
