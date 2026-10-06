<script setup lang="ts">
import { computed } from 'vue'
import type { TraceDetail, TraceNode } from '@/api/traces'
import { fmtCny, fmtMs } from '@/utils/format'
import { NODE_STATUS_COLOR, agentMap, childrenOf, graphEdges, isGraphLandmark, nodeMap } from './traceView'

const props = defineProps<{ detail: TraceDetail; selected: string | null }>()
const emit = defineEmits<{ select: [id: string] }>()

const USER = '__user'
const NODE_W = 124
const NODE_H = 72
const GROUP_W = 226
const USER_W = 70
const COL_GAP = 40
const ROW_GAP = 16

interface Box { id: string; x: number; y: number; w: number; h: number; group: boolean }

const agents = computed(() => agentMap(props.detail))
const byId = computed(() => nodeMap(props.detail))

const edges = computed(() => {
  const list = graphEdges(props.detail)
  if (list.length) return list
  const steps = (props.detail.nodes ?? []).filter((n) => isGraphLandmark(n))
  return steps.slice(1).map((n, i) => ({ from: steps[i].id!, to: n.id!, label: null, atMs: n.startMs, parallel: false }))
})

const graphIds = computed(() => {
  const ids = new Set<string>()
  edges.value.forEach((e) => ids.add(e.from!).add(e.to!))
  if (!ids.size) (props.detail.nodes ?? []).filter((n) => isGraphLandmark(n)).forEach((n) => ids.add(n.id!))
  return [...ids].filter((id) => byId.value[id])
})

const layout = computed(() => {
  const layer: Record<string, number> = {}
  const preds = (id: string) => edges.value.filter((e) => e.to === id).map((e) => e.from!)
  const depthOf = (id: string, seen = new Set<string>()): number => {
    if (layer[id] !== undefined) return layer[id]
    if (seen.has(id)) return 0
    seen.add(id)
    const ps = preds(id).filter((p) => graphIds.value.includes(p))
    return (layer[id] = ps.length ? Math.max(...ps.map((p) => depthOf(p, seen))) + 1 : 0)
  }
  graphIds.value.forEach((id) => depthOf(id))

  const columns: string[][] = []
  graphIds.value.forEach((id) => (columns[layer[id]] ??= []).push(id))
  columns.forEach((col) => col.sort((a, b) => (byId.value[a].startMs ?? 0) - (byId.value[b].startMs ?? 0)))

  const sizeOf = (id: string) => {
    const node = byId.value[id]
    if (!node.aggregated) return { w: NODE_W, h: NODE_H, group: false }
    const rows = Math.ceil(childrenOf(props.detail, node).length / 2)
    return { w: GROUP_W, h: 40 + rows * 30, group: true }
  }
  const heights = columns.map((col) => col.reduce((h, id) => h + sizeOf(id).h, 0) + ROW_GAP * (col.length - 1))
  const height = Math.max(NODE_H, ...heights)

  const boxes: Box[] = [{ id: USER, x: 0, y: (height - 56) / 2, w: USER_W, h: 56, group: false }]
  let x = USER_W + COL_GAP
  columns.forEach((col, i) => {
    let y = (height - heights[i]) / 2
    const width = Math.max(...col.map((id) => sizeOf(id).w))
    col.forEach((id) => {
      const s = sizeOf(id)
      boxes.push({ id, x, y, ...s })
      y += s.h + ROW_GAP
    })
    x += width + COL_GAP
  })
  return { boxes, width: x - COL_GAP, height, sources: columns[0] ?? [] }
})

const boxOf = (id: string) => layout.value.boxes.find((b) => b.id === id)!

const links = computed(() => {
  const all = [
    ...layout.value.sources.map((to) => ({ from: USER, to, label: null as string | null | undefined, color: '#6b7990' })),
    ...edges.value
      .filter((e) => graphIds.value.includes(e.from!) && graphIds.value.includes(e.to!))
      .map((e) => ({ from: e.from!, to: e.to!, label: e.label, color: agents.value[byId.value[e.to!].agentKey!]?.color ?? '#9fb2cf' })),
  ]
  return all.map((l) => {
    const a = boxOf(l.from)
    const b = boxOf(l.to)
    const x1 = a.x + a.w
    const y1 = a.y + a.h / 2
    const x2 = b.x - 2
    const y2 = b.y + b.h / 2
    const mx = (x1 + x2) / 2
    return { ...l, d: `M${x1} ${y1} C${mx} ${y1} ${mx} ${y2} ${x2} ${y2}`, lx: mx, ly: (y1 + y2) / 2 - 4 }
  })
})

const colorOf = (n: TraceNode) => agents.value[n.agentKey!]?.color ?? 'var(--line)'
const meta = (n: TraceNode): string[] =>
  [n.model, `${n.humanWaitLabel ?? fmtMs(n.durationMs)}${n.costCny ? ` · ${fmtCny(n.costCny)}` : ''}`].filter((m): m is string => !!m)
</script>

<template>
  <div style="overflow-x: auto">
    <div class="graph" :style="{ width: `${layout.width}px`, height: `${layout.height}px` }">
      <svg :viewBox="`0 0 ${layout.width} ${layout.height}`" :width="layout.width" :height="layout.height">
        <defs>
          <marker id="ah" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
            <path d="M0 0L8 4L0 8z" fill="#9fb2cf" />
          </marker>
        </defs>
        <template v-for="(l, i) in links" :key="i">
          <path :d="l.d" fill="none" :stroke="l.color" stroke-width="1.6" marker-end="url(#ah)" />
          <text v-if="l.label" :x="l.lx" :y="l.ly" text-anchor="middle" font-size="10.5" fill="#c3cde0" stroke="#101826" stroke-width="4" paint-order="stroke">
            {{ l.label }}
          </text>
        </template>
      </svg>

      <template v-for="b in layout.boxes" :key="b.id">
        <div v-if="b.id === USER" class="gn user" :style="{ left: `${b.x}px`, top: `${b.y}px`, width: `${b.w}px`, height: `${b.h}px` }">
          <div class="t">用户</div>
          <div class="m">{{ detail.summary?.userId }}</div>
        </div>
        <div
          v-else-if="b.group"
          class="gg"
          :style="{ left: `${b.x}px`, top: `${b.y}px`, width: `${b.w}px`, height: `${b.h}px`, borderColor: colorOf(byId[b.id]) }"
        >
          <div class="gh" :class="{ sel: selected === b.id }" :style="{ color: colorOf(byId[b.id]) }" @click="emit('select', b.id)">
            <span class="dot" :style="{ background: colorOf(byId[b.id]) }" />{{ byId[b.id].name }}
            <span class="xsvc">跨服务</span>
            <small>{{ fmtMs(byId[b.id].durationMs) }} · {{ fmtCny(byId[b.id].costCny) }}</small>
          </div>
          <div class="steps2">
            <div v-for="c in childrenOf(detail, byId[b.id])" :key="c.id" class="chipn" :class="{ sel: selected === c.id }" @click="emit('select', c.id!)">
              <span class="dot" :style="{ background: NODE_STATUS_COLOR[c.status ?? 'ok'] }" />{{ c.shortName ?? c.name }}
            </div>
          </div>
        </div>
        <div
          v-else
          class="gn"
          :class="{ sel: selected === b.id }"
          :style="{ left: `${b.x}px`, top: `${b.y}px`, width: `${b.w}px`, height: `${b.h}px`, borderColor: colorOf(byId[b.id]) }"
          @click="emit('select', b.id)"
        >
          <div class="t">{{ byId[b.id].shortName ?? byId[b.id].name }}</div>
          <div class="m"><div v-for="m in meta(byId[b.id])" :key="m">{{ m }}</div></div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.graph { position: relative; margin: 10px auto 0; }
.graph svg { position: absolute; left: 0; top: 0; overflow: visible; }
.gn { position: absolute; border: 1.5px solid var(--line); background: var(--bg); border-radius: 10px; padding: 8px 10px; font-size: 12px; cursor: pointer; box-sizing: border-box; overflow: hidden; }
.gn:hover { background: #111b2b; }
.gn.sel { box-shadow: 0 0 0 2px #fff; }
.gn .t { color: #fff; font-weight: 600; font-size: 12.5px; line-height: 1.35; }
.gn .m { color: var(--mute); font-size: 10.5px; font-family: var(--mono); margin-top: 3px; line-height: 1.4; }
.gn.user { cursor: default; border-style: dashed; border-color: #34445f; }
.gg { position: absolute; border: 1.5px dashed; border-radius: 12px; padding: 8px 10px; background: rgba(10, 16, 26, 0.6); box-sizing: border-box; }
.gh { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; margin-bottom: 8px; cursor: pointer; }
.gh.sel { text-decoration: underline; text-underline-offset: 3px; }
.gh small { font-weight: 400; color: var(--mute); font-family: var(--mono); margin-left: auto; font-size: 10.5px; }
.steps2 { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.chipn { border: 1px solid var(--line); background: var(--panel); border-radius: 6px; padding: 4px 7px; font-size: 11px; color: #c3cde0; cursor: pointer; display: flex; align-items: center; gap: 6px; white-space: nowrap; overflow: hidden; }
.chipn:hover { border-color: #3a4a66; }
.chipn.sel { box-shadow: 0 0 0 1.5px #fff; }
.xsvc { font-size: 9.5px; padding: 0 5px; border-radius: 3px; background: #1f2b40; color: #8fa3c0; font-family: var(--mono); font-weight: 400; }
</style>
