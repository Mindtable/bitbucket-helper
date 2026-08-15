<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'

import NeedsAttention from './components/NeedsAttention.vue'
import ProductHeader, { type ProductOverallStatus } from './components/ProductHeader.vue'
import PullRequestDrawer from './components/PullRequestDrawer.vue'
import RepositoryGroup from './components/RepositoryGroup.vue'
import type { ActionItemSummary, RepositoryGroupModel } from './dashboard.models'
import type { DashboardSource } from './dashboardSource'
import { useDashboard } from './useDashboard'
import { usePullRequestDrawer, type DrawerFocusReturnContext } from './usePullRequestDrawer'

const props = defineProps<{ source: DashboardSource }>()
const { applyAcknowledgment, dispose, pollDashboard, refresh, reload, state } = useDashboard(
  props.source,
)

interface ProductHeaderFocusApi {
  getRefreshControl(): HTMLButtonElement | null
}

interface NeedsAttentionFocusApi {
  getDisclosureControl(): HTMLButtonElement | null
}

interface RepositoryGroupFocusApi {
  getPullRequestReviewControl(pullRequestId: string): HTMLButtonElement | null
}

const pageFallback = ref<HTMLElement | null>(null)
const productHeader = ref<ProductHeaderFocusApi | null>(null)
const needsAttention = ref<NeedsAttentionFocusApi | null>(null)
const repositoryGroups = new Map<string, RepositoryGroupFocusApi>()

function setRepositoryGroupRef(repositoryId: string, instance: unknown) {
  if (
    instance !== null &&
    typeof instance === 'object' &&
    'getPullRequestReviewControl' in instance &&
    typeof instance.getPullRequestReviewControl === 'function'
  ) {
    repositoryGroups.set(repositoryId, instance as RepositoryGroupFocusApi)
  } else {
    repositoryGroups.delete(repositoryId)
  }
}

function connected<T extends HTMLElement>(element: T | null | undefined): T | null {
  return element?.isConnected ? element : null
}

function resolveFocusFallback(context: DrawerFocusReturnContext): HTMLElement | null {
  if (context.origin === 'needsAttention') {
    const disclosure = connected(needsAttention.value?.getDisclosureControl())
    if (disclosure) return disclosure
  }

  const reviewControl = connected(
    repositoryGroups.get(context.repositoryId)?.getPullRequestReviewControl(context.pullRequestId),
  )
  if (reviewControl) return reviewControl

  const refreshControl = connected(productHeader.value?.getRefreshControl())
  if (refreshControl && !refreshControl.disabled) return refreshControl
  return connected(pageFallback.value)
}

const drawer = usePullRequestDrawer(props.source, {
  applyAcknowledgment,
  pollDashboard,
  resolveFocusFallback,
})

onUnmounted(dispose)

watch(
  () => (state.value.type === 'ready' ? state.value.dashboard : null),
  (dashboard) => {
    if (dashboard) drawer.reconcileDashboard(dashboard)
  },
)

function reviewPullRequest(
  repository: RepositoryGroupModel,
  pullRequestId: string,
  invoker: HTMLButtonElement,
) {
  const pullRequest = repository.pullRequests.find(
    (candidate) => candidate.pullRequestId === pullRequestId,
  )
  if (pullRequest) void drawer.openPullRequest(repository, pullRequest, invoker)
}

function reviewActionItem(actionItem: ActionItemSummary, invoker: HTMLButtonElement) {
  if (state.value.type === 'ready') {
    void drawer.openActionItem(state.value.dashboard, actionItem, invoker)
  }
}

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
  <main ref="pageFallback" class="dashboard-shell" tabindex="-1">
    <p v-if="state.type === 'loading'" class="state-panel" role="status" aria-live="polite">
      Loading dashboard…
    </p>
    <section
      v-else-if="state.type === 'ready'"
      class="dashboard-content"
      aria-labelledby="product-heading"
    >
      <ProductHeader
        ref="productHeader"
        :workspace-display-name="state.dashboard.workspaceDisplayName"
        :dashboard-revision="state.dashboard.dashboardRevision"
        :overall-status="overallStatus"
        :refresh-state="state.refresh"
        @refresh="refresh"
      />
      <div class="dashboard-layout">
        <div class="dashboard-feed">
          <NeedsAttention
            ref="needsAttention"
            :items="state.dashboard.inbox"
            @review="reviewActionItem"
          />
          <section class="repository-feed" aria-labelledby="repository-feed-heading">
            <h2 id="repository-feed-heading">Configured repositories</h2>
            <ul class="repository-list" aria-labelledby="repository-feed-heading">
              <li
                v-for="repository in state.dashboard.repositoryGroups"
                :key="repository.repositoryId"
                class="repository-list__item"
              >
                <RepositoryGroup
                  :ref="(instance) => setRepositoryGroupRef(repository.repositoryId, instance)"
                  :repository="repository"
                  @review="
                    (pullRequestId, invoker) =>
                      reviewPullRequest(repository, pullRequestId, invoker)
                  "
                />
              </li>
            </ul>
          </section>
        </div>
        <PullRequestDrawer
          :state="drawer.state.value"
          @close="drawer.close"
          @retry="drawer.retrySelectedContent"
          @acknowledge="drawer.acknowledgeSelected"
          @refresh="drawer.refreshSelectedRepository"
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
    <p class="visually-hidden" data-drawer-status role="status" aria-live="polite">
      {{ drawer.statusMessage.value }}
    </p>
  </main>
</template>
