import { get, post } from './http'

export interface QualityCase {
  traceId: string
  agent: string
  input?: string
  output?: string
  comment?: string | null
  createdAt?: string | null
  added: boolean
}

export interface QualityReport {
  connected: boolean
  feedbackCount: number
  downCount: number
  downRate?: number | null
  cases: QualityCase[]
}

export function getQuality(agent?: string) {
  return get<QualityReport>('/quality', { params: agent ? { agent } : {} })
}

export function addQualityCase(agent: string, traceId: string) {
  return post<{ dataset: string; itemId: string; traceId: string }>('/quality/cases', { agent, traceId })
}
