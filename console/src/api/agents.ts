import { get } from './http'
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
