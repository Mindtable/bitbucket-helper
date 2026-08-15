<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

import type { DrawerUiState } from '../usePullRequestDrawer'
import ActivityOutcome from './ActivityOutcome.vue'
import ReadinessSummary from './ReadinessSummary.vue'

const props = defineProps<{ state: DrawerUiState }>()
const emit = defineEmits<{ close: []; retry: []; acknowledge: []; refresh: [] }>()
const closeButton = ref<HTMLButtonElement | null>(null)
const drawerHeading = ref<HTMLHeadingElement | null>(null)

const context = computed(() => (props.state.type === 'closed' ? null : props.state.context))
const activityKind = computed(() =>
  context.value?.selectedActionItem?.kind === 'changesRequested' ? 'Changes requested' : 'Comment',
)

watch(
  () => props.state.type,
  async (type, previousType) => {
    if (previousType === 'closed' && type !== 'closed') {
      await nextTick()
      closeButton.value?.focus({ preventScroll: true })
      if (window.matchMedia?.('(max-width: 759px)').matches) {
        drawerHeading.value?.scrollIntoView({ block: 'start' })
      }
    }
  },
)
</script>

<template>
  <aside
    v-if="state.type !== 'closed' && context"
    class="pull-request-drawer"
    aria-labelledby="pull-request-drawer-heading"
    @keydown.esc.stop="emit('close')"
  >
    <header class="pull-request-drawer__header">
      <div>
        <p class="eyebrow">{{ context.repositoryDisplayName }}</p>
        <p class="pull-request-number">Pull request #{{ context.pullRequest.displayNumber }}</p>
        <h2 id="pull-request-drawer-heading" ref="drawerHeading">
          {{ context.pullRequest.title }}
        </h2>
      </div>
      <button ref="closeButton" type="button" data-close-drawer @click="emit('close')">
        Close
      </button>
    </header>

    <p class="pull-request-meta">
      By {{ context.pullRequest.authorDisplayName }} · Updated
      <time :datetime="context.pullRequest.updatedAt">{{ context.pullRequest.updatedAt }}</time>
    </p>
    <p>
      <a :href="context.pullRequest.webUrl" target="_blank" rel="noopener noreferrer">
        Open in Bitbucket
      </a>
    </p>

    <ReadinessSummary
      :pull-request="context.pullRequest"
      :readiness-checks="context.detail?.readinessChecks ?? []"
    />

    <section class="drawer-activity" aria-labelledby="drawer-activity-heading">
      <h3 id="drawer-activity-heading">Selected activity</h3>
      <template v-if="context.selectedActionItem">
        <p class="drawer-activity__kind">{{ activityKind }}</p>
        <p>
          {{ context.selectedActionItem.actorDisplayName }} ·
          <time :datetime="context.selectedActionItem.occurredAt">
            {{ context.selectedActionItem.occurredAt }}
          </time>
        </p>
        <p>Activity version {{ context.selectedActionItem.activityVersion }}</p>
        <a :href="context.selectedActionItem.webUrl" target="_blank" rel="noopener noreferrer">
          Open activity in Bitbucket
        </a>
        <ActivityOutcome
          :activity-content="context.activityContent"
          @retry="emit('retry')"
          @acknowledge="emit('acknowledge')"
          @refresh="emit('refresh')"
        />
      </template>
      <p v-else class="empty-state">No actionable activity is selected.</p>
    </section>

    <div class="drawer-detail-status" data-drawer-detail-status role="status" aria-live="polite">
      <p v-if="state.type === 'detailLoading'">Loading pull request details…</p>
      <p v-else-if="state.type === 'detailUnavailable'">{{ state.message }}</p>
    </div>
  </aside>
</template>
