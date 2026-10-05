import { get, post } from './http'
import type { components, operations } from './schema'

export type AgentSummary = components['schemas']['AgentSummary']
export type AgentStatus = components['schemas']['AgentStatus']
export type ListAgentsQuery = NonNullable<operations['listAgents']['parameters']['query']>
export type AgentPage = components['schemas']['PageMeta'] & { items?: AgentSummary[] }

export function listAgents(query: ListAgentsQuery) {
  return get<AgentPage>('/agents', { params: query })
}

export type AgentDetail = components['schemas']['AgentDetail']

export function getAgent(name: string) {
  return get<AgentDetail>(`/agents/${encodeURIComponent(name)}`)
}

export interface SelfCheckReport {
  passed?: boolean
  items?: { name?: string; passed?: boolean; detail?: string | null }[]
}

export function checkAgentName(name: string) {
  return get<{ available?: boolean; reason?: string }>('/agents/name-check', { params: { name } })
}

export function previewManifest(body: Record<string, unknown>) {
  return post<{ yaml: string; warnings?: string[] }>('/agents/manifest-preview', body)
}

export function registerAgent(body: Record<string, unknown>) {
  return post<SelfCheckReport>('/agents', body)
}

export function chatWithAgent(name: string, text: string) {
  return post<{ text?: string; traceId?: string | null }>(`/agents/${encodeURIComponent(name)}/chat`, { text }, { timeout: 60_000 })
}
