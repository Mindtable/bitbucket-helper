<script setup lang="ts">
import type { ActivityContentState } from '../usePullRequestDrawer'

defineProps<{ activityContent: ActivityContentState | null }>()
const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div v-if="activityContent" class="activity-outcome">
    <p v-if="activityContent.type === 'contentLoading'" role="status">Loading activity…</p>
    <template v-else-if="activityContent.type === 'contentUnavailable'">
      <p>{{ activityContent.message }}</p>
      <button v-if="activityContent.retryable" type="button" @click="emit('retry')">
        Try again
      </button>
    </template>
    <p v-else-if="activityContent.type === 'newerActivity'">
      Newer activity is available. Current version {{ activityContent.currentActivityVersion }}.
    </p>
    <pre v-else class="activity-outcome__markdown" data-activity-markdown>{{
      activityContent.markdownSource
    }}</pre>
  </div>
</template>
