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
    <header class="needs-attention-header">
      <div class="needs-attention-title">
        <p class="eyebrow">Inbox</p>
        <h2 id="needs-attention-heading">Needs attention</h2>
      </div>
      <button
        type="button"
        class="needs-attention-toggle"
        :aria-expanded="expanded"
        aria-controls="needs-attention-body"
        aria-labelledby="needs-attention-heading needs-attention-count"
        @click="expanded = !expanded"
      >
        <span id="needs-attention-count" class="needs-attention-count">
          {{ items.length }} open
        </span>
        <span class="needs-attention-chevron" aria-hidden="true">⌄</span>
      </button>
    </header>
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
