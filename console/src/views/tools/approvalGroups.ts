import type { Approval, SuspendedRun } from '@/api/approvals'
import type { DevflowJob, DevflowReview } from '@/api/devflow'

export type Group = 'all' | 'devflow' | 'tool' | 'agent' | 'data' | 'human'
export type Gate = DevflowReview['gate']

export const GROUPS: [Group, string][] = [
  ['all', '全部'],
  ['devflow', '研发任务'],
  ['tool', '工具审批'],
  ['agent', '智能体审批'],
  ['data', '数据导出'],
  ['human', '人工介入'],
]

export const SUBJECT_GROUP: Record<NonNullable<Approval['subjectType']>, Group> = {
  'tool.call': 'tool',
  'tool.config': 'tool',
  'agent.config': 'agent',
  'agent.retire': 'agent',
  'data.export': 'data',
}

export const GATE_LABEL: Record<Gate, string> = {
  H1: '需求确认',
  H2: '评测确认',
  H4: '发布审批',
  PR: '协作 review',
}

export interface DevflowItem {
  key: string
  jobId: string
  gate: Gate
  title: string
  targetAgent: string
  createdAt?: string
}

export function approvalGroup(approval: Pick<Approval, 'subjectType' | 'devflowJobId'>): Group {
  if (approval.devflowJobId) return 'devflow'
  return SUBJECT_GROUP[approval.subjectType!]
}

export function runGroup(run: Pick<SuspendedRun, 'devflowJobId'>): Group {
  return run.devflowJobId ? 'devflow' : 'human'
}

export function reviewJobs(jobs: DevflowJob[]): DevflowJob[] {
  return jobs.filter((job) => job.status === 'WAIT' && job.needReview)
}

export function devflowItems(approvals: Approval[], runs: SuspendedRun[], jobs: DevflowJob[]): DevflowItem[] {
  const byId = new Map(jobs.map((job) => [job.jobId, job]))
  const item = (key: string, jobId: string, gate: Gate, createdAt?: string): DevflowItem => {
    const job = byId.get(jobId)
    return { key, jobId, gate, title: job?.title ?? '', targetAgent: job?.targetAgent ?? '—', createdAt }
  }
  return [
    ...runs.filter((run) => run.devflowJobId).map((run) => item(`run:${run.runId}`, run.devflowJobId!, run.devflowGate ?? 'H1', run.createdAt)),
    ...approvals.filter((approval) => approval.devflowJobId).map((approval) => item(`ap:${approval.id}`, approval.devflowJobId!, approval.devflowGate ?? 'H4', approval.createdAt)),
    ...reviewJobs(jobs).map((job) => item(`pr:${job.jobId}`, job.jobId, 'PR')),
  ]
}

export function shownApprovals(group: Group, approvals: Approval[]): Approval[] {
  if (group === 'devflow') return []
  return approvals.filter((approval) => {
    const own = approvalGroup(approval)
    return own !== 'devflow' && (group === 'all' || own === group)
  })
}

export function shownRuns(group: Group, runs: SuspendedRun[]): SuspendedRun[] {
  if (group !== 'all' && group !== 'human') return []
  return runs.filter((run) => runGroup(run) === 'human')
}

export function shownDevflow(group: Group, items: DevflowItem[]): DevflowItem[] {
  return group === 'all' || group === 'devflow' ? items : []
}

export function groupCount(group: Group, approvals: Approval[], runs: SuspendedRun[], items: DevflowItem[]): number {
  return shownApprovals(group, approvals).length + shownRuns(group, runs).length + shownDevflow(group, items).length
}

/** Devflow H1 / H2 / H4 are already in the approvals and runs totals; only collaboration reviews are extra. */
export function pendingTotal(approvalTotal: number, runTotal: number, jobs: DevflowJob[]): number {
  return approvalTotal + runTotal + reviewJobs(jobs).length
}

export function reviewPath(jobId: string): string {
  return `/approvals/devflow/${encodeURIComponent(jobId)}`
}

export function ringColor(score: number, minScore: number): string {
  return score >= minScore ? 'var(--ok)' : 'var(--bad)'
}

export function ringDash(score: number, radius = 30): string {
  const circumference = 2 * Math.PI * radius
  return `${circumference * Math.max(0, Math.min(1, score))} ${circumference}`
}

export function holdoutCount(humanCount: number, ratio: number): number {
  return Math.round(humanCount * ratio)
}

export function defaultSelection(cases: { caseId: string }[]): Record<string, boolean> {
  return Object.fromEntries(cases.map((item) => [item.caseId, true]))
}

export function acceptedIds(selection: Record<string, boolean>): string[] {
  return Object.keys(selection).filter((key) => selection[key])
}

export function canSendBack(note: string): boolean {
  return note.trim().length > 0
}
