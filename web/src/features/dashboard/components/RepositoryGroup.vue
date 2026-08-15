<script setup lang="ts">
import { computed } from 'vue'
import type { RepositoryGroupModel } from '../dashboard.models'
import PullRequestCard from './PullRequestCard.vue'

const props = defineProps<{ repository: RepositoryGroupModel }>()
const headingId = computed(() => 'repository-' + props.repository.repositoryId)
const synchronizationLabel = computed(() => {
  switch (props.repository.synchronization.type) {
    case 'idle':
      return 'Synchronization idle'
    case 'queued':
      return 'Synchronization queued'
    case 'running':
      return 'Synchronization running'
  }
  return ''
})
const freshnessLabel = computed(() => {
  switch (props.repository.freshness.type) {
    case 'neverSynchronized':
      return 'Never synchronized'
    case 'fresh':
      return 'Fresh · ' + props.repository.freshness.ageDescription
    case 'stale':
      return 'Stale · ' + props.repository.freshness.ageDescription
  }
  return ''
})
</script>

<template>
  <section class="repository-group" :aria-labelledby="headingId">
    <header class="repository-header">
      <div>
        <p class="eyebrow">Repository</p>
        <h3 :id="headingId">{{ repository.displayName }}</h3>
      </div>
      <a :href="repository.webUrl" target="_blank" rel="noopener noreferrer">Open repository</a>
    </header>
    <dl class="repository-status">
      <div>
        <dt>Synchronization</dt>
        <dd>{{ synchronizationLabel }}</dd>
      </div>
      <div>
        <dt>Freshness</dt>
        <dd>{{ freshnessLabel }}</dd>
      </div>
    </dl>
    <p v-if="repository.pullRequests.length === 0" class="empty-state">No open pull requests.</p>
    <div v-else class="pull-request-list">
      <PullRequestCard
        v-for="pullRequest in repository.pullRequests"
        :key="pullRequest.pullRequestId"
        :pull-request="pullRequest"
      />
    </div>
  </section>
</template>
