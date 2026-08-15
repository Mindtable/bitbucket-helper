<script setup lang="ts">
import { computed } from 'vue'

import type { ActivityContentState } from '../usePullRequestDrawer'

const props = defineProps<{ activityContent: ActivityContentState | null }>()
const emit = defineEmits<{ retry: []; acknowledge: []; refresh: [] }>()

const acknowledgmentStatus = computed(() => {
  const activityContent = props.activityContent
  if (activityContent?.type === 'ackPending') {
    return `Acknowledging ${activityContent.activityVersion}…`
  }
  if (
    activityContent?.type === 'acknowledged' ||
    activityContent?.type === 'acknowledgmentRejected'
  ) {
    return activityContent.message
  }
  return ''
})
</script>

<template>
  <template v-if="activityContent">
    <p class="visually-hidden" data-acknowledgment-status aria-live="polite">
      {{ acknowledgmentStatus }}
    </p>
    <div class="activity-outcome" data-drawer-content-status aria-live="polite">
      <p v-if="activityContent.type === 'contentLoading'" role="status">Loading activity…</p>
      <template v-else-if="activityContent.type === 'contentUnavailable'">
        <p>{{ activityContent.message }}</p>
        <button v-if="activityContent.retryable" type="button" @click="emit('retry')">
          Try again
        </button>
      </template>
      <template v-else-if="activityContent.type === 'newerActivity'">
        <p>
          Newer activity is available. Current version
          {{ activityContent.currentActivityVersion }}.
        </p>
        <button type="button" @click="emit('refresh')">Refresh</button>
      </template>
      <template v-else-if="activityContent.type === 'contentAvailable'">
        <pre class="activity-outcome__markdown" data-activity-markdown>{{
          activityContent.markdownSource
        }}</pre>
        <button type="button" @click="emit('acknowledge')">
          Acknowledge {{ activityContent.activityVersion }}
        </button>
      </template>
      <template v-else-if="activityContent.type === 'ackPending'">
        <pre class="activity-outcome__markdown" data-activity-markdown>{{
          activityContent.markdownSource
        }}</pre>
        <button type="button" disabled>Acknowledging {{ activityContent.activityVersion }}…</button>
      </template>
      <p v-else-if="activityContent.type === 'acknowledged'">
        {{ activityContent.message }}
      </p>
      <p v-else-if="activityContent.type === 'acknowledgmentRejected'">
        {{ activityContent.message }}
      </p>
      <p v-else role="status">
        Refreshing activity at {{ activityContent.currentActivityVersion }}…
      </p>
    </div>
  </template>
</template>
