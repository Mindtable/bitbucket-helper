<script setup lang="ts">
import { ref } from 'vue'

import type { ActionItemSummary } from '../dashboard.models'
import AttentionItem from './AttentionItem.vue'

defineProps<{ items: readonly ActionItemSummary[] }>()
const emit = defineEmits<{
  review: [actionItem: ActionItemSummary, invoker: HTMLButtonElement]
}>()

const expanded = ref(true)
</script>

<template>
  <section class="needs-attention" aria-labelledby="needs-attention-heading">
    <button
      type="button"
      class="needs-attention-toggle"
      :aria-expanded="expanded"
      aria-controls="needs-attention-body"
      @click="expanded = !expanded"
    >
      <span class="needs-attention-title">
        <span class="eyebrow">Inbox</span>
        <span id="needs-attention-heading" class="needs-attention-heading">Needs attention</span>
      </span>
      <span class="needs-attention-count">{{ items.length }} open</span>
      <span class="needs-attention-chevron" aria-hidden="true">⌄</span>
    </button>
    <div v-if="expanded" id="needs-attention-body" class="needs-attention-body">
      <ul v-if="items.length > 0" class="attention-list">
        <li v-for="item in items" :key="item.actionItemId">
          <AttentionItem
            :item="item"
            @review="(actionItem, invoker) => emit('review', actionItem, invoker)"
          />
        </li>
      </ul>
      <p v-else class="empty-state">You're all caught up.</p>
    </div>
  </section>
</template>
