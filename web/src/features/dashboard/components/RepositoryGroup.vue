<script setup lang="ts">
import { computed } from 'vue'
import type { RepositoryGroupModel } from '../dashboard.models'
import PullRequestCard from './PullRequestCard.vue'

const props = defineProps<{ repository: RepositoryGroupModel }>()
const emit = defineEmits<{
  review: [pullRequestId: string, invoker: HTMLButtonElement]
}>()
const headingId = computed(() => 'repository-' + props.repository.repositoryId)

function assertNever(state: never): never {
  throw new Error('Unexpected repository state: ' + JSON.stringify(state))
}

const synchronizationLabel = computed(() => {
  const synchronization = props.repository.synchronization
  switch (synchronization.type) {
    case 'idle':
      return 'Synchronization idle'
    case 'queued':
      return 'Synchronization queued'
    case 'running':
      return 'Synchronization running'
  }
  return assertNever(synchronization)
})
const freshnessLabel = computed(() => {
  const freshness = props.repository.freshness
  switch (freshness.type) {
    case 'neverSynchronized':
      return 'Never synchronized'
    case 'fresh':
      return 'Fresh · ' + freshness.ageDescription
    case 'stale':
      return 'Stale · ' + freshness.ageDescription
  }
  return assertNever(freshness)
})
</script>

<template>
  <section class="repository-group" :aria-labelledby="headingId">
    <header class="repository-header" data-tree-parent>
      <div>
        <p class="eyebrow">Repository</p>
        <h3 :id="headingId">{{ repository.displayName }}</h3>
      </div>
      <a
        :href="repository.webUrl"
        :aria-label="'Open ' + repository.displayName + ' repository in a new tab'"
        target="_blank"
        rel="noopener noreferrer"
      >
        Open repository
      </a>
    </header>
    <dl class="repository-status">
      <div>
        <dt>Synchronization</dt>
        <dd :data-synchronization-state="repository.synchronization.type">
          {{ synchronizationLabel }}
        </dd>
      </div>
      <div>
        <dt>Freshness</dt>
        <dd :data-freshness-state="repository.freshness.type">{{ freshnessLabel }}</dd>
      </div>
    </dl>
    <p v-if="repository.problem.type === 'present'" class="repository-problem" role="status">
      {{ repository.problem.message }}
    </p>
    <p v-if="repository.pullRequests.length === 0" class="empty-state">No open pull requests.</p>
    <ul v-else class="pull-request-list" data-tree-children>
      <li
        v-for="pullRequest in repository.pullRequests"
        :key="pullRequest.pullRequestId"
        class="pull-request-branch"
      >
        <PullRequestCard
          :pull-request="pullRequest"
          @review="(pullRequestId, invoker) => emit('review', pullRequestId, invoker)"
        />
      </li>
    </ul>
  </section>
</template>
