import { get, post } from './http'
import type { components, operations } from './schema'

export type ToolSummary = components['schemas']['ToolSummary']
export type ToolDetail = components['schemas']['ToolDetail']
export type ListToolsQuery = NonNullable<operations['listTools']['parameters']['query']>
export type ToolPage = components['schemas']['PageMeta'] & { items?: ToolSummary[] }

export function listTools(query: ListToolsQuery) {
  return get<ToolPage>('/tools', { params: query })
}

export function getTool(name: string) {
  return get<ToolDetail>(`/tools/${encodeURIComponent(name)}`)
}

export function deprecateTool(name: string, replacedBy: string, deadline: string) {
  return post<unknown>(`/tools/${encodeURIComponent(name)}/deprecate`, { replacedBy, deadline })
}

export function retireTool(name: string) {
  return post<unknown>(`/tools/${encodeURIComponent(name)}/retire`)
}

export function registerTool(body: {
  name: string
  description?: string
  scope: 'PRIVATE' | 'SHARED'
  access: 'READ' | 'WRITE' | 'EXEC'
  risk: 'LOW' | 'MID' | 'HIGH'
  provider: string
  ownerAgent?: string
  ownerOrg?: string
  schemaJson: Record<string, unknown>
}) {
  return post<{ name?: string }>('/tools', body)
}

export function publishToolVersion(name: string, body: {
  version: string
  description?: string
  schemaJson?: Record<string, unknown>
  access?: 'READ' | 'WRITE' | 'EXEC'
  risk?: 'LOW' | 'MID' | 'HIGH'
  breaking: boolean
}) {
  return post<{ triggeredRegressions?: string[] }>(`/tools/${encodeURIComponent(name)}/versions`, body)
}
