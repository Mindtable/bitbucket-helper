<script setup lang="ts">
import { computed } from 'vue'

import type { ActionItemSummary } from '../dashboard.models'

const props = defineProps<{ item: ActionItemSummary }>()
const emit = defineEmits<{
  review: [actionItem: ActionItemSummary, invoker: HTMLButtonElement]
}>()

const kindLabel = computed(() =>
  props.item.kind === 'changesRequested' ? 'Changes requested' : 'Comment',
)
const acknowledgmentLabel = computed(() =>
  props.item.acknowledgmentState === 'acknowledged' ? 'Acknowledged' : 'Actionable',
)

function review(event: MouseEvent) {
  emit('review', props.item, event.currentTarget as HTMLButtonElement)
}
</script>

<template>
  <button
    type="button"
    class="attention-item"
    :data-action-item-id="item.actionItemId"
    @click="review"
  >
    <span class="attention-item__context">
      Repository {{ item.repositoryId }} · Pull request {{ item.pullRequestId }}
    </span>
    <span class="attention-item__activity">{{ kindLabel }}</span>
    <span class="attention-item__meta">
      {{ item.actorDisplayName }} · <time :datetime="item.occurredAt">{{ item.occurredAt }}</time>
    </span>
    <span class="attention-item__acknowledgment">{{ acknowledgmentLabel }}</span>
  </button>
</template>
