import { get, post, put } from './http'
import type { components } from './schema'

export type DevflowJob = components['schemas']['DevflowJob']
export type DevflowJobList = components['schemas']['DevflowJobList']
export type DevflowBatch = components['schemas']['DevflowBatch']
export type DevflowBatchPreviewRow = components['schemas']['DevflowBatchPreviewRow']
export type DevflowSettings = components['schemas']['DevflowSettings']
export type DevflowReview = components['schemas']['DevflowReview']
export type DevflowSeedResult = { humanCount: number; holdoutCount: number; acceptedAgentCount: number }

// Devflow execution is backed by the server ledger.

export function listDevflowJobs() {
  return get<DevflowJobList>('/devflow/jobs')
}

export function createDevflowJob(body: {
  title: string
  targetAgent: string
  layer: 'DEV' | 'BIZ'
  kind: 'CREATE' | 'CHANGE'
  mode: 'AUTO' | 'COLLAB' | 'SCAFFOLD'
  goal: string
  template: string
  dailyBudgetCny: number
  seedCount: number
  tools: string[]
  knowledge: string[]
  ownerOrg: string
  users?: string
  io?: string
  success?: string
  execution?: boolean
}) {
  return post<DevflowJob>('/devflow/jobs', body)
}

export function getDevflowJob(jobId: string) {
  return get<DevflowJob>(`/devflow/jobs/${encodeURIComponent(jobId)}`)
}

export function takeoverDevflowJob(jobId: string) {
  return post<DevflowJob>(`/devflow/jobs/${encodeURIComponent(jobId)}/takeover`)
}

export function handbackDevflowJob(jobId: string) {
  return post<DevflowJob>(`/devflow/jobs/${encodeURIComponent(jobId)}/handback`)
}

export function assistDevflowJob(jobId: string, instruction: string) {
  return post<DevflowJob>(`/devflow/jobs/${encodeURIComponent(jobId)}/assist`, { instruction })
}

export function cancelDevflowJob(jobId: string) {
  return post<DevflowJob>(`/devflow/jobs/${encodeURIComponent(jobId)}/cancel`)
}

export function getDevflowReview(jobId: string) {
  return get<DevflowReview>(`/devflow/jobs/${encodeURIComponent(jobId)}/review`)
}

export function acceptDevflowSeedCases(jobId: string, acceptedCaseIds: string[]) {
  return post<DevflowSeedResult>(`/devflow/jobs/${encodeURIComponent(jobId)}/seed-cases`, { acceptedCaseIds })
}

export function listDevflowBatches() {
  return get<{ items: DevflowBatch[] }>('/devflow/batches')
}

export function previewDevflowBatch() {
  return post<{ rows: DevflowBatchPreviewRow[] }>('/devflow/batches/preview', {})
}

export function createDevflowBatch(title: string, rows: DevflowBatchPreviewRow[]) {
  return post<DevflowBatch>('/devflow/batches', { title, rows })
}

export function getDevflowSettings() {
  return get<DevflowSettings>('/devflow/settings')
}

export function updateDevflowSettings(body: DevflowSettings) {
  return put<DevflowSettings>('/devflow/settings', body)
}

export type DevflowExecution = components['schemas']['DevflowExecution']
export function getDevflowExecution(jobId: string) {
  return get<DevflowExecution | null>(`/devflow/jobs/${encodeURIComponent(jobId)}/execution`)
}
export function decideDevflowRequirement(jobId: string, body: components['schemas']['DevflowRequirementDecision']) {
  return post<DevflowExecution>(`/devflow/jobs/${encodeURIComponent(jobId)}/requirement`, body)
}
export function retryDevflowExecution(jobId: string) {
  return post<DevflowExecution>(`/devflow/jobs/${encodeURIComponent(jobId)}/retry`)
}
