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

export function agentMap(detail: TraceDetail): Record<string, TraceAgent> {
  return Object.fromEntries((detail.agents ?? []).map((a) => [a.key!, { ...a, color: avatarColor(a.name) }]))
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
