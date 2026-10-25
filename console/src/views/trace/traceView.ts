import type { TraceDetail, TraceNode } from '@/api/traces'
import { avatarColor } from '@/views/agents/agentDrawer'

export type TraceAgent = NonNullable<TraceDetail['agents']>[number]
export type TraceRange = '1h' | '24h' | '7d'
export type TraceTab = 'graph' | 'lanes' | 'tree'

const RANGE_MS: Record<TraceRange, number> = { '1h': 3_600_000, '24h': 86_400_000, '7d': 7 * 86_400_000 }

export const NODE_STATUS_COLOR: Record<string, string> = {
  ok: 'var(--ok)',
  fallback: 'var(--warn)',
  failed: 'var(--bad)',
}

export function traceWindow(range: TraceRange, now = Date.now()) {
  return { from: new Date(now - RANGE_MS[range]).toISOString(), to: new Date(now).toISOString() }
}

export function humanWait(ms: number) {
  return `${Math.floor(ms / 60_000)}m${Math.round((ms % 60_000) / 1000)}s`
}

export function showTokens(n: number | null | undefined) {
  return n ? n.toLocaleString('en-US') : '—'
}

export function userLine(userId?: string | null, userRole?: string | null) {
  return [userId, userRole].filter((part) => !!part).join(' ')
}

export function agentChain(agents?: string[]) {
  if (!agents?.length) return ''
  return agents.length === 1 ? agents[0] : `${agents[0]} → ${agents.slice(1).join('、')}`
}

export function sourceText(jobId?: string | null, label?: string | null) {
  if (!jobId) return ''
  return label ? `${jobId} · ${label}` : jobId
}

export interface TraceJobPlaceholder {
  traceId: string
  rootAgent: string
  jobId: string
  label: string
  duration: string
  cost: string
}

/** Prototype rows used when a job is selected and no trace carries that job id yet. */
export function traceJobPlaceholders(jobId: string): TraceJobPlaceholder[] {
  if (!jobId) return []
  return [
    { traceId: '4f2a…91c', rootAgent: 'dev-lead', jobId, label: '第 2 轮门禁', duration: '6m12s', cost: '¥2.10' },
    { traceId: 'a81e…03d', rootAgent: 'dev-lead', jobId, label: '修复', duration: '11m40s', cost: '¥5.80' },
  ]
}

export function initialTab(multi: boolean): TraceTab {
  return multi ? 'graph' : 'lanes'
}

export function pickNode(nodes: TraceNode[]): string | null {
  const chosen = nodes.find((n) => n.status && n.status !== 'ok' && !n.aggregated)
    ?? nodes.find((n) => n.humanWaitLabel)
    ?? nodes.find((n) => n.type === 'generation')
    ?? nodes.at(-1)
  return chosen?.id ?? null
}

export function longestStep(steps: { label?: string; ms?: number }[] | undefined, durationMs?: number | null) {
  const top = [...(steps ?? [])].sort((a, b) => (b.ms ?? 0) - (a.ms ?? 0))[0]
  if (!top?.label || !top.ms || !durationMs) return null
  return { label: top.label, pct: Math.round((top.ms / durationMs) * 100) }
}

export function observationUrl(base?: string | null, id?: string | null) {
  if (!base || !id) return ''
  try {
    const url = new URL(base)
    url.searchParams.set('observation', id)
    return url.toString()
  } catch {
    return ''
  }
}

const STEP_ALPHA = ['', 'cc', '99', '77']
const PALETTE = ['#5b9cf6', '#2ec4b6', '#ff7a45', '#34c38f', '#b48cf2', '#e36fae', '#6fd3e3', '#ffb08f']

/** Keep the color keel-server assigned. Known agents stay on the prototype palette; others are not flattened to gray. */
export function agentMap(detail: TraceDetail): Record<string, TraceAgent> {
  const root = detail.summary?.rootAgent
  const multi = !!detail.summary?.multiAgent
  const used = new Set<string>()
  return Object.fromEntries((detail.agents ?? []).map((agent) => {
    const delegated = agent.kind === 'sub'
      || (!!multi && !!agent.key && agent.key !== root && agent.kind !== 'human' && agent.kind !== 'platform')
    const color = distinctColor(agent.color || avatarColor(agent.name), used)
    const shown: TraceAgent = delegated
      ? { ...agent, kind: 'sub', subtitle: '子智能体 · 跨服务', color }
      : { ...agent, color }
    return [agent.key!, shown]
  }))
}

function distinctColor(color: string, used: Set<string>) {
  if (!used.has(color)) {
    used.add(color)
    return color
  }
  const start = Math.max(PALETTE.indexOf(color), 0)
  for (let step = 1; step <= PALETTE.length; step += 1) {
    const next = PALETTE[(start + step) % PALETTE.length]
    if (!used.has(next)) {
      used.add(next)
      return next
    }
  }
  return color
}

/** Same agent repeating in the latency stack gets a lighter step, matching the prototype shade cycle. */
export function stepColor(color: string | null | undefined, index: number) {
  const base = color && /^#[0-9a-fA-F]{6}$/.test(color) ? color : '#8a97ab'
  return `${base}${STEP_ALPHA[index % STEP_ALPHA.length]}`
}

export function breakdownColors(steps: { agentKey?: string | null }[] | undefined, colorOf: (key?: string | null) => string | undefined) {
  const seen: Record<string, number> = {}
  return (steps ?? []).map((step) => {
    const key = step.agentKey ?? ''
    const index = seen[key] ?? 0
    seen[key] = index + 1
    return stepColor(colorOf(key), index)
  })
}

const LANE_ROW = 18

export function isAgentSpan(node: TraceNode) {
  return (node.type ?? '').toLowerCase() === 'agent'
}

/** Swimlane bars. Overlapping steps take another row instead of painting on top of each other. */
export function laneRows(nodes: TraceNode[], agentKey: string) {
  const mine = nodes.filter((node) => node.agentKey === agentKey)
  const spans = mine.filter((node) => isAgentSpan(node) && (node.durationMs ?? 0) > 0)
  const waits = mine.filter((node) => node.humanWaitLabel && !isAgentSpan(node))
  const ends: number[] = []
  const bars = mine
    .filter((node) => !isAgentSpan(node) && !node.humanWaitLabel)
    .sort((a, b) => (a.startMs ?? 0) - (b.startMs ?? 0) || (b.durationMs ?? 0) - (a.durationMs ?? 0))
    .map((node) => {
      const start = node.startMs ?? 0
      const end = start + Math.max(node.durationMs ?? 0, 0)
      let row = ends.findIndex((until) => until <= start)
      if (row < 0) {
        row = ends.length
        ends.push(end)
      } else {
        ends[row] = end
      }
      return { node, row, top: 6 + row * LANE_ROW }
    })
  const rows = Math.max(1, ends.length)
  return { spans, waits, bars, height: rows <= 2 ? 44 : 8 + rows * (LANE_ROW + 2) }
}

export function nodeMap(detail: TraceDetail): Record<string, TraceNode> {
  return Object.fromEntries((detail.nodes ?? []).map((n) => [n.id!, n]))
}

/** Children of an aggregated agent node: same agent, one level deeper. */
export function childrenOf(detail: TraceDetail, group: TraceNode): TraceNode[] {
  return (detail.nodes ?? []).filter((n) => n.agentKey === group.agentKey && n.depth === (group.depth ?? 0) + 1 && !n.aggregated)
}

/** Boxes on the collaboration graph. Nested steps stay inside their agent group. */
export function isGraphLandmark(node: TraceNode): boolean {
  if (node.humanWaitLabel) return true
  const depth = node.depth ?? 0
  if (node.aggregated) return depth > 0
  return depth < 2
}

type Edge = NonNullable<TraceDetail['edges']>[number]

export function graphEdges(detail: TraceDetail): Edge[] {
  const byId = nodeMap(detail)
  const parent = new Map<string, string>()
  for (const edge of detail.edges ?? []) {
    if (edge.from && edge.to) parent.set(edge.to, edge.from)
  }
  const landmark = (id: string): string | null => {
    const seen = new Set<string>()
    let cur: string | undefined = id
    while (cur && !seen.has(cur)) {
      seen.add(cur)
      const node = byId[cur]
      if (node && isGraphLandmark(node)) return cur
      cur = parent.get(cur)
    }
    return null
  }
  const lifted: Edge[] = []
  const seen = new Set<string>()
  for (const edge of detail.edges ?? []) {
    const from = edge.from ? landmark(edge.from) : null
    const to = edge.to ? landmark(edge.to) : null
    if (!from || !to || from === to) continue
    const key = `${from}>${to}`
    if (seen.has(key)) continue
    seen.add(key)
    lifted.push({ ...edge, from, to })
  }
  return lifted
}

/** Collaboration graph edges. Delegation edges stay; a single agent falls back to its steps in time order. */
export function displayEdges(detail: TraceDetail): Edge[] {
  const lifted = graphEdges(detail)
  if (lifted.length) return lifted
  const steps = (detail.nodes ?? [])
    .filter((node) => isGraphLandmark(node) && node.id)
    .sort((a, b) => (a.startMs ?? 0) - (b.startMs ?? 0) || String(a.id).localeCompare(String(b.id)))
  return steps.slice(1).map((node, index) => ({
    from: steps[index].id,
    to: node.id,
    label: null,
    atMs: node.startMs,
    parallel: false,
  }))
}

/** Render explicit evidence without mistaking a retrieval hit for context use. */
export function memoryEvidenceText(evidence: Record<string, string>) {
  const labels: Record<string, string> = { operation: '步骤', status: '处理状态', version: '版本', cursorId: '已处理至消息', sourceIds: '原文消息编号', count: '数量', pending: '待确认事实', superseded: '已被替代事实', gaps: '未覆盖消息数', more: '仍有待处理历史', sha256: '内容 SHA256', offset: '原文起始位置', totalChars: '原文总字符数', used: '摘要已加入模型输入', tokens: '组装后输入 token', budget: '输入上限', taskId: '记忆维护任务', triggerTraceId: '触发请求链路', targetId: '目标消息上界', attempt: '尝试次数', reason: '处理说明', calls: '维护模型调用数', costUpperBoundCny: '本次费用保守上界（元）', inputTokens: '维护输入 token', outputTokens: '维护输出 token' }
  return Object.entries(evidence).map(([key, value]) => `${labels[key] ?? key}：${value}`).join('\n')
}
