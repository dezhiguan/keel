<script setup lang="ts">
import { computed } from 'vue'
import type { TraceDetail, TraceNode } from '@/api/traces'
import { fmtMs } from '@/utils/format'
import { agentMap, nodeMap } from './traceView'

const props = defineProps<{ detail: TraceDetail; selected: string | null }>()
const emit = defineEmits<{ select: [id: string] }>()

const ROW_H = 44
const AXIS_H = 22
const NAME_W = 128

const total = computed(() => props.detail.summary?.durationMs || 1)
const agents = computed(() => agentMap(props.detail))
const order = computed(() => (props.detail.agents ?? []).map((a) => a.key!))
const pct = (ms = 0) => (ms / total.value) * 100

const lanes = computed(() =>
  order.value.map((key) => {
    const nodes = (props.detail.nodes ?? []).filter((n) => n.agentKey === key)
    const ends: number[] = []
    const bars = nodes
      .filter((n) => n.type !== 'agent' && !n.humanWaitLabel)
      .sort((a, b) => (a.startMs ?? 0) - (b.startMs ?? 0))
      .map((n) => {
        const start = n.startMs ?? 0
        let row = ends.findIndex((end) => end <= start)
        if (row < 0) row = ends.push(0) - 1
        ends[row] = start + (n.durationMs ?? 0)
        return { node: n, top: 6 + Math.min(row, 1) * 18 }
      })
    return {
      key,
      agent: agents.value[key],
      spans: nodes.filter((n) => n.type === 'agent'),
      waits: nodes.filter((n) => n.humanWaitLabel),
      bars,
    }
  }),
)

const byId = computed(() => nodeMap(props.detail))

const handoffs = computed(() =>
  (props.detail.edges ?? []).flatMap((e) => {
    const from = byId.value[e.from!]
    const to = byId.value[e.to!]
    if (!from || !to || from.agentKey === to.agentKey || e.atMs === null || e.atMs === undefined) return []
    const i = order.value.indexOf(from.agentKey!)
    const j = order.value.indexOf(to.agentKey!)
    return [{ left: e.atMs / total.value, top: AXIS_H + Math.min(i, j) * ROW_H + ROW_H / 2, height: Math.abs(j - i) * ROW_H, down: j > i }]
  }),
)

const ticks = computed(() => [0, 0.25, 0.5, 0.75, 1].map((p) => fmtMs(Math.round(total.value * p))))
const colorOf = (n: TraceNode) => agents.value[n.agentKey!]?.color ?? '#8a97ab'
</script>

<template>
  <div class="lanes">
    <div class="laxis"><span /><div><span v-for="t in ticks" :key="t">{{ t }}</span></div></div>
    <div v-for="lane in lanes" :key="lane.key" class="lrow">
      <div class="lname2">
        <span class="dot" :style="{ background: lane.agent?.color }" />
        <div>{{ lane.agent?.name }}<small>{{ lane.agent?.subtitle }}</small></div>
      </div>
      <div class="ltrack">
        <div
          v-for="s in lane.spans"
          :key="s.id"
          class="lagent"
          :style="{ left: `${pct(s.startMs)}%`, width: `${pct(s.durationMs)}%`, borderColor: colorOf(s), background: `${colorOf(s)}14` }"
        />
        <div
          v-for="w in lane.waits"
          :key="w.id"
          class="lwait"
          :class="{ sel: selected === w.id }"
          :style="{ left: `${pct(w.startMs)}%` }"
          @click="emit('select', w.id!)"
        >
          ⏸ {{ w.shortName ?? w.name }} {{ w.humanWaitLabel }}（不计入耗时）
        </div>
        <div
          v-for="b in lane.bars"
          :key="b.node.id"
          class="lb2"
          :class="{ fb: b.node.status === 'fallback', bad: b.node.status === 'failed', crit: b.node.criticalPath, sel: selected === b.node.id }"
          :title="`${b.node.name} · ${fmtMs(b.node.durationMs)}`"
          :style="{ left: `${pct(b.node.startMs)}%`, width: `${Math.max(pct(b.node.durationMs), 0.35)}%`, top: `${b.top}px`, background: colorOf(b.node) }"
          @click="emit('select', b.node.id!)"
        >
          {{ b.node.shortName ?? b.node.name }}
        </div>
      </div>
    </div>
    <div
      v-for="(h, i) in handoffs"
      :key="i"
      class="hand"
      :class="h.down ? 'down' : 'up'"
      :style="{ left: `calc(${NAME_W}px + (100% - ${NAME_W}px) * ${h.left})`, top: `${h.top}px`, height: `${h.height}px` }"
    />
  </div>
</template>

<style scoped>
.lanes { position: relative; margin-top: 12px; }
.laxis { display: grid; grid-template-columns: 128px 1fr; height: 22px; font-family: var(--mono); font-size: 10px; color: var(--mute); border-bottom: 1px solid var(--line); }
.laxis div { display: flex; justify-content: space-between; }
.lrow { display: grid; grid-template-columns: 128px 1fr; height: 44px; border-bottom: 1px solid #182236; }
.lname2 { font-size: 12px; color: #e6ebf3; display: flex; align-items: center; gap: 7px; }
.lname2 small { display: block; color: var(--mute); font-size: 10px; line-height: 1.2; }
.ltrack { position: relative; background: repeating-linear-gradient(90deg, transparent 0, transparent calc(25% - 1px), #162033 calc(25% - 1px), #162033 25%); }
.lagent { position: absolute; top: 3px; bottom: 3px; border: 1px dashed; border-radius: 6px; box-sizing: border-box; }
.lb2 { position: absolute; height: 16px; border-radius: 4px; font-size: 10px; color: #07111c; padding: 0 5px; line-height: 16px; white-space: nowrap; overflow: hidden; cursor: pointer; font-weight: 600; box-sizing: border-box; }
.lb2.fb { outline: 1.5px dashed var(--warn); outline-offset: 1px; }
.lb2.bad { outline: 1.5px solid var(--bad); outline-offset: 1px; }
.lb2.crit { border-bottom: 3px solid #fff; }
.lb2.sel { box-shadow: 0 0 0 2px #fff; }
.lwait { position: absolute; top: 13px; transform: translateX(-100%); font-size: 10.5px; color: var(--warn); background: rgba(241, 180, 76, 0.12); border-right: 3px solid var(--warn); padding: 1px 7px; border-radius: 4px 0 0 4px; white-space: nowrap; cursor: pointer; }
.lwait.sel { box-shadow: 0 0 0 1.5px #fff; }
.hand { position: absolute; width: 0; border-left: 1.5px dashed #9fb2cf; pointer-events: none; }
.hand::after { content: ''; position: absolute; left: -4.5px; width: 0; height: 0; border-left: 4px solid transparent; border-right: 4px solid transparent; }
.hand.down::after { bottom: -1px; border-top: 6px solid #9fb2cf; }
.hand.up::after { top: -1px; border-bottom: 6px solid #9fb2cf; }
</style>
