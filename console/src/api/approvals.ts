import { get, post } from './http'
import type { components } from './schema'

export type Approval = components['schemas']['Approval']
export type SuspendedRun = components['schemas']['SuspendedRun']
type Page<T> = components['schemas']['PageMeta'] & { items?: T[] }

export function listApprovals(query: { status?: Approval['status']; page?: number; size?: 10 | 20 | 50 | 100 }) {
  return get<Page<Approval>>('/approvals', { params: query })
}

export function decideApproval(id: string, decision: 'APPROVE' | 'REJECT', comment?: string) {
  return post<Approval>(`/approvals/${encodeURIComponent(id)}/decision`, { decision, comment })
}

export function listSuspendedRuns(query: { page?: number; size?: 10 | 20 | 50 | 100 }) {
  return get<Page<SuspendedRun>>('/runs', { params: query })
}

export function answerSuspendedRun(runId: string, text: string) {
  return post<unknown>(`/runs/${encodeURIComponent(runId)}/input`, { text })
}
