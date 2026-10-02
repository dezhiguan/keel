<script setup lang="ts">
import { computed } from 'vue'
import StatusPill from '@/components/StatusPill.vue'
import type { ToolSummary } from '@/api/tools'
import type { StatusTone } from '@/utils/format'

const props = defineProps<{ status: ToolSummary['status'] }>()

const TOOL_STATUS: Record<NonNullable<ToolSummary['status']>, { label: string; tone: StatusTone }> = {
  REGISTERED: { label: '已注册', tone: 'idle' },
  ONLINE: { label: '上线', tone: 'ok' },
  DEPRECATED: { label: '已废弃', tone: 'degraded' },
  RETIRED: { label: '已下线', tone: 'idle' },
}

const pill = computed(() => (props.status ? TOOL_STATUS[props.status] : { label: '—', tone: 'idle' as const }))
</script>

<template>
  <StatusPill v-bind="pill" />
</template>
