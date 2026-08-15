<script setup lang="ts">
import { computed } from 'vue'

import type { BuildState } from '../dashboard.models'

const props = defineProps<{ buildState: BuildState; controlId: string }>()
const descriptionId = computed(() => 'build-details-' + props.controlId)

function assertNever(state: never): never {
  throw new Error('Unexpected build state: ' + JSON.stringify(state))
}
</script>

<template>
  <template v-if="buildState.type === 'successful'">
    <span data-build-status="successful" class="build-status build-status--successful"
      >Build successful</span
    >
  </template>
  <template v-else-if="buildState.type === 'inProgress'">
    <span data-build-status="in-progress" class="build-status build-status--in-progress"
      >Build in progress</span
    >
  </template>
  <template v-else-if="buildState.type === 'unavailable'">
    <span data-build-status="unavailable" class="build-status build-status--unavailable">
      Build unavailable: {{ buildState.reason }}
    </span>
  </template>
  <template v-else-if="buildState.type === 'failed'">
    <span data-build-status="failed" class="build-status build-status--failed">
      <span class="failure-marker" aria-hidden="true">!</span>Build failed
    </span>
    <span v-if="buildState.failedCheckCount !== undefined">
      {{ buildState.failedCheckCount }} failed checks
    </span>
    <button
      type="button"
      data-view-build
      aria-disabled="true"
      :aria-describedby="descriptionId"
      @click.prevent
    >
      View build
    </button>
    <span :id="descriptionId" class="visually-hidden">
      Build details are not available in Bitbucket Helper yet.
    </span>
  </template>
  <template v-else>{{ assertNever(buildState) }}</template>
</template>
