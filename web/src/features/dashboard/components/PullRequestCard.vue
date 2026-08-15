<script setup lang="ts">
import { computed } from 'vue'
import type { PullRequestSummary } from '../dashboard.models'
import BuildStatus from './BuildStatus.vue'

const props = defineProps<{ pullRequest: PullRequestSummary }>()
const emit = defineEmits<{
  review: [pullRequestId: string, invoker: HTMLButtonElement]
}>()

const readinessLabel = computed(() =>
  props.pullRequest.readiness.type === 'available'
    ? props.pullRequest.readiness.passed + ' of ' + props.pullRequest.readiness.total + ' checks'
    : 'Readiness unavailable: ' + props.pullRequest.readiness.reason,
)
const actionItemLabel = computed(
  () =>
    props.pullRequest.actionableItemCount +
    ' actionable item' +
    (props.pullRequest.actionableItemCount === 1 ? '' : 's'),
)
const acknowledgedItemLabel = computed(
  () =>
    props.pullRequest.acknowledgedItemCount +
    ' acknowledged item' +
    (props.pullRequest.acknowledgedItemCount === 1 ? '' : 's'),
)

function review(event: MouseEvent) {
  emit('review', props.pullRequest.pullRequestId, event.currentTarget as HTMLButtonElement)
}
</script>

<template>
  <article class="pull-request-card" :data-pull-request-id="pullRequest.pullRequestId">
    <header>
      <p class="pull-request-number">#{{ pullRequest.displayNumber }}</p>
      <h4>{{ pullRequest.title }}</h4>
      <p class="pull-request-meta">
        By {{ pullRequest.authorDisplayName }} · Updated
        <time :datetime="pullRequest.updatedAt">{{ pullRequest.updatedAt }}</time>
      </p>
    </header>
    <ul class="pull-request-status">
      <li>{{ readinessLabel }}</li>
      <li>
        <BuildStatus
          :build-state="pullRequest.buildState"
          :control-id="pullRequest.pullRequestId"
        />
      </li>
      <li>{{ actionItemLabel }}</li>
      <li>{{ acknowledgedItemLabel }}</li>
    </ul>
    <footer class="pull-request-actions">
      <button type="button" data-review-context @click="review">Review context</button>
      <a :href="pullRequest.webUrl" target="_blank" rel="noopener noreferrer">Open in Bitbucket</a>
    </footer>
  </article>
</template>
