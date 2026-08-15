<script setup lang="ts">
import DashboardView from '@/features/dashboard/DashboardView.vue'
import type { DashboardSource } from '@/features/dashboard/dashboardSource'

defineProps<{
  dashboardSource: DashboardSource
}>()
</script>

<template>
  <DashboardView :source="dashboardSource" />
</template>
