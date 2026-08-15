<script setup lang="ts">
import { computed } from 'vue'

import type { ActivityContentState } from '../usePullRequestDrawer'

const props = defineProps<{ activityContent: ActivityContentState | null }>()
const emit = defineEmits<{ retry: []; acknowledge: []; refresh: [] }>()

function assertNever(state: never): never {
  throw new Error('Unexpected activity content state: ' + JSON.stringify(state))
}

const activityAnnouncement = computed(() => {
  const activityContent = props.activityContent
  if (!activityContent) return ''

  switch (activityContent.type) {
    case 'contentLoading':
      return `Loading activity ${activityContent.activityVersion}…`
    case 'contentUnavailable':
      return activityContent.message
    case 'newerActivity':
      return `Newer activity is available. Current version ${activityContent.currentActivityVersion}.`
    case 'contentAvailable':
      return `Activity content loaded for ${activityContent.activityVersion}.`
    case 'ackPending':
      return `Acknowledging ${activityContent.activityVersion}…`
    case 'acknowledged':
    case 'acknowledgmentRejected':
      return activityContent.message
    case 'refreshing':
      return `Refreshing activity at ${activityContent.currentActivityVersion}…`
  }
  return assertNever(activityContent)
})
</script>

<template>
  <p class="visually-hidden" data-activity-announcement role="status" aria-live="polite">
    {{ activityAnnouncement }}
  </p>
  <div v-if="activityContent" class="activity-outcome">
    <p v-if="activityContent.type === 'contentLoading'">Loading activity…</p>
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
    <p v-else>Refreshing activity at {{ activityContent.currentActivityVersion }}…</p>
  </div>
</template>
