import { describe, expect, it } from 'vitest'
import type { Approval, SuspendedRun } from '@/api/approvals'
import type { DevflowJob } from '@/api/devflow'
import {
  GROUPS,
  acceptedIds,
  canSendBack,
  defaultSelection,
  devflowItems,
  groupCount,
  holdoutCount,
  pendingTotal,
  reviewPath,
  ringColor,
  ringDash,
  shownApprovals,
  shownDevflow,
  shownRuns,
} from './approvalGroups'

function job(partial: Partial<DevflowJob> & Pick<DevflowJob, 'jobId' | 'status' | 'stage'>): DevflowJob {
  return {
    title: '样例',
    layer: 'BIZ',
    kind: 'CREATE',
    mode: 'AUTO',
    targetAgent: 'sample',
    producerAgent: 'dev-lead',
    template: 'tool-agent',
    spentCny: 0,
    budgetCny: 80,
    fixRounds: 0,
    maxFixRounds: 3,
    needReview: false,
    requester: 'amy',
    ownerOrg: '研发效能组',
    goal: '目标',
    tools: [],
    knowledge: [],
    seed: { human: 0, agent: 0, holdout: 0 },
    events: [],
    ...partial,
  }
}

const approvals: Approval[] = [
  { id: 'ap_1', subjectType: 'tool.call', subjectRef: 'work_order_create', status: 'PENDING' },
  { id: 'ap_2', subjectType: 'tool.config', subjectRef: 'sql.legacy_export', status: 'PENDING' },
  { id: 'ap_3', subjectType: 'agent.config', subjectRef: 'offshore-wind', status: 'PENDING' },
  { id: 'ap_4', subjectType: 'agent.retire', subjectRef: 'night-patrol', status: 'PENDING' },
  { id: 'ap_5', subjectType: 'data.export', subjectRef: 'audit_export', status: 'PENDING' },
  { id: 'ap_6', subjectType: 'tool.call', subjectRef: 'git.pr.merge', status: 'PENDING', devflowJobId: 'DF-0017', devflowGate: 'H4', createdAt: '2026-10-08T01:00:00Z' },
]
const runs: SuspendedRun[] = [
  { runId: 'r_1', reason: 'input_required' },
  { runId: 'r_2', reason: 'handoff' },
  { runId: 'r_3', reason: 'input_required', devflowJobId: 'DF-0018', devflowGate: 'H1' },
  { runId: 'r_4', reason: 'input_required', devflowJobId: 'DF-0016', devflowGate: 'H2' },
]
const jobs = [
  job({ jobId: 'DF-0021', status: 'WAIT', stage: 'REVIEW', needReview: true, targetAgent: 'dev-agent', title: '升级 dev-agent' }),
  job({ jobId: 'DF-0018', status: 'WAIT', stage: 'H1', targetAgent: 'kb-curator', title: '知识库巡检' }),
  job({ jobId: 'DF-0016', status: 'WAIT', stage: 'H2', targetAgent: 'sql-explainer' }),
  job({ jobId: 'DF-0017', status: 'WAIT', stage: 'H4', targetAgent: 'oncall-handoff' }),
  job({ jobId: 'DF-0015', status: 'HUMAN', stage: 'BUILD', needReview: true }),
]
const items = devflowItems(approvals, runs, jobs)

describe('approval groups', () => {
  it('puts the research group right after all', () => {
    expect(GROUPS.map(([key]) => key)).toEqual(['all', 'devflow', 'tool', 'agent', 'data', 'human'])
  })

  it('keeps the five existing groups unchanged for data without devflow fields', () => {
    const plain = approvals.filter((a) => !a.devflowJobId)
    const plainRuns = runs.filter((r) => !r.devflowJobId)
    expect(shownApprovals('tool', plain).map((a) => a.id)).toEqual(['ap_1', 'ap_2'])
    expect(shownApprovals('agent', plain).map((a) => a.id)).toEqual(['ap_3', 'ap_4'])
    expect(shownApprovals('data', plain).map((a) => a.id)).toEqual(['ap_5'])
    expect(shownApprovals('human', plain)).toEqual([])
    expect(shownRuns('human', plainRuns)).toEqual(plainRuns)
    expect(shownRuns('tool', plainRuns)).toEqual([])
    expect(groupCount('all', plain, plainRuns, [])).toBe(7)
  })

  it('moves devflow approvals and runs out of their old groups', () => {
    expect(shownApprovals('tool', approvals).map((a) => a.id)).not.toContain('ap_6')
    expect(shownApprovals('all', approvals).map((a) => a.id)).not.toContain('ap_6')
    expect(shownRuns('human', runs).map((r) => r.runId)).toEqual(['r_1', 'r_2'])
    expect(shownApprovals('devflow', approvals)).toEqual([])
    expect(shownRuns('devflow', runs)).toEqual([])
  })

  it('builds one devflow item per gate plus waiting collaboration reviews', () => {
    expect(items.map((item) => [item.jobId, item.gate])).toEqual([
      ['DF-0018', 'H1'],
      ['DF-0016', 'H2'],
      ['DF-0017', 'H4'],
      ['DF-0021', 'PR'],
    ])
    expect(items[0]).toMatchObject({ targetAgent: 'kb-curator', title: '知识库巡检' })
    expect(devflowItems([], [{ runId: 'r_9', devflowJobId: 'DF-9999' }], []).at(0)).toMatchObject({ gate: 'H1', targetAgent: '—' })
  })

  it('counts every item once', () => {
    expect(groupCount('devflow', approvals, runs, items)).toBe(4)
    expect(groupCount('tool', approvals, runs, items)).toBe(2)
    expect(groupCount('human', approvals, runs, items)).toBe(2)
    expect(groupCount('all', approvals, runs, items)).toBe(approvals.length + runs.length + 1)
    expect(shownDevflow('tool', items)).toEqual([])
    expect(shownDevflow('all', items)).toHaveLength(4)
  })

  it('adds only collaboration reviews to the pending badge', () => {
    expect(pendingTotal(6, 4, jobs)).toBe(11)
    expect(pendingTotal(6, 4, [])).toBe(10)
  })

  it('links to the gate page', () => {
    expect(reviewPath('DF-0018')).toBe('/approvals/devflow/DF-0018')
  })
})

describe('gate page helpers', () => {
  it('colours the score ring against the gate minimum', () => {
    expect(ringColor(0.86, 0.8)).toBe('var(--ok)')
    expect(ringColor(0.8, 0.8)).toBe('var(--ok)')
    expect(ringColor(0.74, 0.8)).toBe('var(--bad)')
    const full = 2 * Math.PI * 30
    expect(ringDash(1.4)).toBe(`${full} ${full}`)
    expect(ringDash(-1)).toBe(`0 ${full}`)
  })

  it('selects every case by default and returns only checked ids', () => {
    const selection = defaultSelection([{ caseId: 'a01' }, { caseId: 'a02' }, { caseId: 'a05' }])
    expect(acceptedIds(selection)).toEqual(['a01', 'a02', 'a05'])
    selection.a05 = false
    expect(acceptedIds(selection)).toEqual(['a01', 'a02'])
  })

  it('rounds the holdout count and requires a note to send H1 back', () => {
    expect(holdoutCount(30, 0.3)).toBe(9)
    expect(holdoutCount(36, 0.3)).toBe(11)
    expect(canSendBack('  ')).toBe(false)
    expect(canSendBack('补充 io')).toBe(true)
  })
})
