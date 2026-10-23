import { describe, expect, it } from 'vitest'
import { traceDetail } from '@/mocks/data/traces'
import {
  agentChain,
  agentMap,
  breakdownColors,
  displayEdges,
  graphEdges,
  laneRows,
  sourceText,
  stepColor,
  traceJobPlaceholders,
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
import type { TraceNode } from '@/api/traces'

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
    expect(sourceText('DF-0019', '第 2 轮门禁')).toBe('DF-0019 · 第 2 轮门禁')
    expect(sourceText('DF-0019', '')).toBe('DF-0019')
    expect(sourceText('', '修复')).toBe('')
    expect(traceJobPlaceholders('')).toEqual([])
    expect(traceJobPlaceholders('DF-0019').map((row) => row.label)).toEqual(['第 2 轮门禁', '修复'])
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

  it('keeps server colors and shades repeated steps the way the prototype stack does', () => {
    const detail = traceDetail('tr_8a31f0c2')!
    const mapped = agentMap({
      ...detail,
      agents: [{ key: 'qa', name: 'qa-release-knowledge-1017', color: '#5b9cf6', kind: 'supervisor' }],
    })
    expect(mapped.qa.color).toBe('#5b9cf6')
    const shared = agentMap({
      summary: { rootAgent: 'coordinator', multiAgent: true },
      agents: [
        { key: 'coordinator', name: 'coordinator', color: '#34c38f', kind: 'supervisor', subtitle: 'dev · supervisor' },
        { key: 'investigator', name: 'investigator', color: '#6fd3e3', kind: 'supervisor', subtitle: 'dev · 单智能体' },
        { key: 'planner', name: 'planner', color: '#6fd3e3', kind: 'supervisor', subtitle: 'dev · 单智能体' },
      ],
    })
    expect(shared.investigator.kind).toBe('sub')
    expect(shared.investigator.subtitle).toBe('子智能体 · 跨服务')
    expect(shared.planner.color).not.toBe(shared.investigator.color)
    expect(shared.coordinator.kind).toBe('supervisor')
    expect(stepColor('#5b9cf6', 0)).toBe('#5b9cf6')
    expect(stepColor('#5b9cf6', 1)).toBe('#5b9cf6cc')
    expect(stepColor('#5b9cf6', 4)).toBe('#5b9cf6')
    expect(breakdownColors(
      [{ agentKey: 'sup' }, { agentKey: 'wind' }, { agentKey: 'sup' }],
      (key) => (key === 'wind' ? '#2ec4b6' : '#ff7a45'),
    )).toEqual(['#ff7a45', '#2ec4b6', '#ff7a45cc'])
  })

  it('stacks overlapping swimlane bars on extra rows and chains a single-agent graph', () => {
    const nodes: TraceNode[] = [
      { id: 'root', agentKey: 'a', type: 'agent', name: 'root', startMs: 0, durationMs: 1000, depth: 0, aggregated: true },
      { id: 'chat', agentKey: 'a', type: 'generation', name: 'llm.chat', startMs: 0, durationMs: 900, depth: 1 },
      { id: 'search', agentKey: 'a', type: 'retriever', name: 'knowledge.search', startMs: 0, durationMs: 80, depth: 1 },
      { id: 'late', agentKey: 'a', type: 'tool', name: 'catalog', startMs: 0, durationMs: 40, depth: 1 },
    ]
    const lane = laneRows(nodes, 'a')
    expect(lane.spans.map((node) => node.id)).toEqual(['root'])
    expect(lane.bars.map((bar) => bar.row)).toEqual([0, 1, 2])
    expect(lane.height).toBeGreaterThan(44)
    const chained = displayEdges({
      nodes,
      edges: [
        { from: 'root', to: 'chat', atMs: 0, parallel: false },
        { from: 'root', to: 'search', atMs: 0, parallel: false },
      ],
    })
    expect(chained.map((edge) => `${edge.from}>${edge.to}`)).toEqual(['chat>late', 'late>search'])
    expect(graphEdges({ nodes, edges: chained })).toEqual(chained)
  })

  it('keeps the Langfuse observation link on the trace url', () => {
    expect(observationUrl('https://jp.cloud.langfuse.com/project/p/traces/tr', 'n1'))
      .toBe('https://jp.cloud.langfuse.com/project/p/traces/tr?observation=n1')
    expect(observationUrl('', 'n1')).toBe('')
  })
})
import { memoryEvidenceText } from './traceView'
it('shows memory versions and references without treating a hit as a used summary', () => {
  const text = memoryEvidenceText({ operation: 'reference.read', version: '3', sourceIds: '["17"]', count: '1' })
  expect(text).toContain('版本：3')
  expect(text).toContain('原文消息编号：["17"]')
  expect(text).not.toContain('摘要已加入模型输入')
})
