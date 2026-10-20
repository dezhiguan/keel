<script setup lang="ts">
import { computed } from 'vue'
import type { components } from '@/api/schema'
const props = defineProps<{ status?: components['schemas']['InsightDataStatus']; loading?: boolean; failed?: boolean; subject: string }>()
const busy = computed(() => props.loading || (!props.failed && props.status?.state === 'loading'))
const text = computed(() => {
  if (props.failed || props.status?.state === 'unavailable') return `${props.subject}暂无法更新，请重试`
  if (props.status?.state === 'stale') return '更新暂有延迟，当前展示上次同步结果'
  if (props.status?.state === 'loading') return props.subject === '链路' ? '历史同步中，数量为当前已同步记录，稍后自动更新' : '统计数据同步中，稍后自动更新'
  return props.loading ? '正在更新' : ''
})
</script>
<template>
  <span class="insight-sync" role="status" aria-live="polite">
    <span v-if="text">{{ text }}</span>
    <span v-if="status?.updatedAt">更新于 {{ new Date(status.updatedAt).toLocaleTimeString('zh-CN', { hour12: false }) }}</span>
    <span v-if="busy" class="insight-spinner" aria-label="加载中" />
    <slot />
  </span>
</template>
<style scoped>
.insight-sync { display: inline-flex; align-items: center; flex-wrap: wrap; gap: 8px; color: var(--mut); font-size: 12px; }
.insight-spinner { width: 12px; height: 12px; flex: 0 0 12px; border: 2px solid currentColor; border-right-color: transparent; border-radius: 50%; animation: insight-spin .8s linear infinite; }
@keyframes insight-spin { to { transform: rotate(360deg); } }
</style>
