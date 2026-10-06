import { describe, expect, it } from 'vitest'
import { traceDetail } from '@/mocks/data/traces'
import {
  agentChain,
  graphEdges,
  humanWait,
  initialTab,
  isGraphLandmark,
  longestStep,
  observationUrl,
  pickNode,
  showTokens,
  traceWindow,
  userLine,
} from './traceView'

describe('trace view helpers', () => {
  it('builds a time window and the list labels the prototype uses', () => {
    const now = Date.parse('2026-10-07T01:00:00.000Z')
    expect(traceWindow('24h', now)).toEqual({
      from: '2026-10-06T01:00:00.000Z',
      to: '2026-10-07T01:00:00.000Z',
    })
    expect(humanWait(252_000)).toBe('4m12s')
    expect(showTokens(0)).toBe('—')
    expect(showTokens(10578)).toBe('10,578')
    expect(userLine('u_88', '运维工程师')).toBe('u_88 运维工程师')
    expect(agentChain(['ops-copilot', 'askdb', 'offshore-wind'])).toBe('ops-copilot → askdb、offshore-wind')
  })

  it('hides the collaboration graph for a single agent and picks a real node', () => {
    expect(initialTab(false)).toBe('lanes')
    expect(initialTab(true)).toBe('graph')
    const detail = traceDetail('tr_8a31f0c2')!
    expect(pickNode(detail.nodes!)).toBe('w4')
    const leaves = detail.nodes!.filter((node) => node.id === 'w1' || node.id === 'a1')
    expect(leaves.every((node) => !isGraphLandmark(node))).toBe(true)
    expect(isGraphLandmark(detail.nodes!.find((node) => node.id === 'askdb')!)).toBe(true)
    const edges = graphEdges(detail)
    const ids = new Set(detail.nodes!.filter(isGraphLandmark).map((node) => node.id))
    expect(edges.every((edge) => ids.has(edge.from) && ids.has(edge.to))).toBe(true)
    expect(longestStep(detail.latencyBreakdown, detail.summary?.durationMs)?.label).toBe('offshore-wind')
  })

  it('keeps the Langfuse observation link on the trace url', () => {
    expect(observationUrl('https://jp.cloud.langfuse.com/project/p/traces/tr', 'n1'))
      .toBe('https://jp.cloud.langfuse.com/project/p/traces/tr?observation=n1')
    expect(observationUrl('', 'n1')).toBe('')
  })
})