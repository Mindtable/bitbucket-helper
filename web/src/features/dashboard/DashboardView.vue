<script setup lang="ts">
import RepositoryGroup from './components/RepositoryGroup.vue'
import type { DashboardSource } from './dashboardSource'
import { useDashboard } from './useDashboard'

const props = defineProps<{ source: DashboardSource }>()
const { reload, state } = useDashboard(props.source)
</script>

<template>
  <main class="dashboard-shell">
    <header class="dashboard-header">
      <p class="eyebrow">Bitbucket Helper</p>
      <h1>Pull requests</h1>
    </header>
    <p v-if="state.type === 'loading'" class="state-panel" role="status" aria-live="polite">
      Loading dashboard…
    </p>
    <section
      v-else-if="state.type === 'ready'"
      class="dashboard-content"
      aria-labelledby="workspace-heading"
    >
      <header class="workspace-header">
        <div>
          <p class="eyebrow">Workspace</p>
          <h2 id="workspace-heading">{{ state.dashboard.workspaceDisplayName }}</h2>
        </div>
        <p class="snapshot-time">
          Snapshot
          <time :datetime="state.dashboard.generatedAt">{{ state.dashboard.generatedAt }}</time>
        </p>
      </header>
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
