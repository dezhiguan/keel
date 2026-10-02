<script setup lang="ts">
import { computed } from 'vue'
import StatusPill from '@/components/StatusPill.vue'
import type { TraceDetail } from '@/api/traces'
import { fmtCny, fmtMs, nodeStatus } from '@/utils/format'
import { agentMap } from './traceView'

const props = defineProps<{ detail: TraceDetail; selected: string | null }>()
const emit = defineEmits<{ select: [id: string] }>()

const agents = computed(() => agentMap(props.detail))
const rootId = computed(() => (props.detail.nodes ?? []).find((n) => n.aggregated && (n.depth ?? 0) === 0)?.id)
</script>

<template>
  <div class="tree">
    <div class="tr hd"><span>名称</span><span>Langfuse 类型</span><span>智能体</span><span>耗时</span><span>成本</span><span>状态</span></div>
    <div
      v-for="n in detail.nodes"
      :key="n.id"
      class="tr"
      :class="{ sel: selected === n.id }"
      @click="emit('select', n.id!)"
    >
      <span class="nmc">
        <span :style="{ display: 'inline-block', width: `${(n.depth ?? 0) * 18}px` }" />
        <span v-if="n.depth" class="tw">└</span>
        <span>{{ n.name }}</span>
        <span v-if="n.aggregated && n.id !== rootId" class="xsvc">跨服务</span>
      </span>
      <span><span class="ot" :class="`ot-${n.type}`">{{ n.type }}</span></span>
      <span><span class="dot" :style="{ background: agents[n.agentKey!]?.color }" /> {{ agents[n.agentKey!]?.name }}</span>
      <span class="mono">{{ n.humanWaitLabel ?? fmtMs(n.durationMs) }}</span>
      <span class="mono">{{ fmtCny(n.costCny) }}</span>
      <span><StatusPill v-bind="nodeStatus(n.status)" /></span>
    </div>
  </div>
</template>

<style scoped>
.tree { margin-top: 10px; min-width: 680px; }
.tr { display: grid; grid-template-columns: 1fr 90px 112px 64px 70px 70px; gap: 8px; padding: 6px 8px; border-bottom: 1px solid #182236; font-size: 12px; align-items: center; cursor: pointer; }
.tr:hover { background: #152036; }
.tr.sel { background: #1a2740; }
.tr.hd { color: var(--mute); font-size: 11px; cursor: default; background: none; }
.nmc { display: flex; align-items: center; gap: 6px; color: #e6ebf3; white-space: nowrap; overflow: hidden; }
.tw { color: #4c5a70; font-family: var(--mono); }
.xsvc { font-size: 9.5px; padding: 0 5px; border-radius: 3px; background: #1f2b40; color: #8fa3c0; font-family: var(--mono); }
.ot { font-family: var(--mono); font-size: 9.5px; padding: 0 5px; border-radius: 3px; line-height: 16px; display: inline-block; background: #222d42; color: #a9b5c9; }
.ot-agent { background: rgba(255, 122, 69, 0.18); color: var(--acc); }
.ot-generation { background: rgba(199, 146, 234, 0.18); color: #c792ea; }
.ot-tool { background: rgba(46, 196, 182, 0.16); color: var(--teal); }
.ot-retriever { background: rgba(91, 156, 246, 0.16); color: var(--soft); }
.ot-guardrail { background: rgba(241, 180, 76, 0.16); color: var(--warn); }
</style>
