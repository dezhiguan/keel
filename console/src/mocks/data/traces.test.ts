import { describe, expect, it } from 'vitest'
import { traceDetail, traceSummaries } from './traces'
import { childrenOf } from '@/views/trace/traceView'

describe('trace mocks follow the TraceDetail contract', () => {
  for (const summary of traceSummaries) {
    const detail = traceDetail(summary.traceId!)!

    it(`${summary.traceId}: latency breakdown sums to durationMs`, () => {
      const sum = (detail.latencyBreakdown ?? []).reduce((n, l) => n + (l.ms ?? 0), 0)
      expect(sum).toBe(summary.durationMs)
    })

    it(`${summary.traceId}: edges and agent keys point at existing entries`, () => {
      const ids = new Set(detail.nodes?.map((n) => n.id))
      const agents = new Set(detail.agents?.map((a) => a.key))
      for (const e of detail.edges ?? []) {
        expect(ids.has(e.from)).toBe(true)
        expect(ids.has(e.to)).toBe(true)
      }
      for (const n of detail.nodes ?? []) expect(agents.has(n.agentKey)).toBe(true)
    })
  }

  it('groups sub-agent steps under their aggregated agent node', () => {
    const detail = traceDetail('tr_8a31f0c2')!
    const wind = detail.nodes!.find((n) => n.id === 'wind')!
    expect(childrenOf(detail, wind).map((n) => n.id)).toEqual(['w1', 'w2', 'w3', 'w4', 'w5'])
  })

  it('returns null for an unknown trace', () => {
    expect(traceDetail('tr_missing')).toBeNull()
  })
})
