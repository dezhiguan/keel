import type { TraceDetail, TraceNode } from '@/api/traces'

export type TraceAgent = NonNullable<TraceDetail['agents']>[number]

export const NODE_STATUS_COLOR: Record<string, string> = {
  ok: 'var(--ok)',
  fallback: 'var(--warn)',
  failed: 'var(--bad)',
}

export function agentMap(detail: TraceDetail): Record<string, TraceAgent> {
  return Object.fromEntries((detail.agents ?? []).map((a) => [a.key!, a]))
}

export function nodeMap(detail: TraceDetail): Record<string, TraceNode> {
  return Object.fromEntries((detail.nodes ?? []).map((n) => [n.id!, n]))
}

/** Children of an aggregated agent node: same agent, one level deeper. */
export function childrenOf(detail: TraceDetail, group: TraceNode): TraceNode[] {
  return (detail.nodes ?? []).filter((n) => n.agentKey === group.agentKey && n.depth === (group.depth ?? 0) + 1 && !n.aggregated)
}
