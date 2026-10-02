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
