<script setup lang="ts">
defineProps<{ current: 'board' | 'batches' | 'rules' }>()

const tabs = [
  { id: 'board', label: '看板', to: '/jobs' },
  { id: 'batches', label: '批次', to: '/jobs/batches' },
  { id: 'rules', label: '规则', to: '/jobs/rules' },
] as const
</script>

<template>
  <div class="tabsline">
    <RouterLink v-for="tab in tabs" :key="tab.id" :to="tab.to" :class="{ on: tab.id === current }">{{ tab.label }}</RouterLink>
  </div>
</template>
