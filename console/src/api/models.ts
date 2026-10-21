import { downloadText, get, post } from './http'
import type { components, operations } from './schema'

export type ModelGateway = components['schemas']['ModelGateway']
export type ModelGatewayQuery = NonNullable<operations['getModelGateway']['parameters']['query']>

export function getModelGateway(query: ModelGatewayQuery) {
  return get<ModelGateway>('/insight/costs', { params: query })
}

export function updateModelBudget(alias: string, body: {
  dailyBudgetCny?: number
  rpm?: number | null
  tpm?: number | null
  concurrency?: number | null
  channels?: { id: string; rpm: number }[]
}) {
  return post<{ alias: string; dailyBudgetCny?: number }>(`/insight/costs/keys/${encodeURIComponent(alias)}/budget`, body)
}

export function probeModel(name: string) {
  return post<{ name: string; health: string }>(`/insight/models/${encodeURIComponent(name)}/probe`)
}

export function createAlertRule(body: { name: string; metric: string; threshold: number; window?: string; groupBy?: string }) {
  return post<{ id: string; name: string }>('/insight/alert-rules', body)
}

export function exportModelBills(query: ModelGatewayQuery) {
  return downloadText('/insight/costs/export', { params: query })
}

export function rotateModelKey(alias: string) {
  return post<{ alias: string; key: string; version: number }>(`/keys/${encodeURIComponent(alias)}/rotate`, {})
}

export function publishRoutePolicy(objective: 'balanced' | 'cost' | 'latency') {
  return post<{ version: number; objective: string }>('/insight/route-policy', { objective })
}

export function rollbackRoutePolicy() {
  return post<{ version: number; objective: string }>('/insight/route-policy/rollback', {})
}

export function previewRoute(alias: string, model: string) {
  return post<{ version: number; objective: string; models: string[] }>('/insight/route/preview', { alias, model })
}

export function addModelChannel(body: { name: string; model: string; env: string; rpm: number }) {
  return post<{ id: string; name: string }>('/insight/channels', body)
}

export function switchProdChannel(id: string) {
  return post<{ moved: number; name: string; id: string }>('/insight/channels/switch', { id })
}

export function simulateChannelFailures(id: string) {
  return post<{ id: string; breaker: string; openUntil: number }>('/insight/channels/simulate', { id })
}

export function probeModelChannel(id: string) {
  return post<{ id: string; breaker: string; openUntil: number }>('/insight/channels/probe', { id })
}

export type LogicalRule = components['schemas']['LogicalRoutingRule']
export type LogicalRouteSnapshot = Required<Omit<components['schemas']['LogicalRoutingSnapshot'], 'gaps'>> & {
  gaps: { alias: string; logical: string; models: string[] }[]
}
export type RouteReplay = {
  requests: number; published: Record<string, number>; draft: Record<string, number>
  missingAuthorization: number; unmatched: number; pricedRequests: number
  publishedEstimatedCny: number; draftEstimatedCny: number; legacyRequests: number
  recordLimit: number; windowHours: number; version: number; revision: number
}
export function getLogicalRouteTable() { return get<LogicalRouteSnapshot>('/insight/route-table') }
export function saveLogicalRouteTable(revision: number, rules: LogicalRule[]) {
  return post<LogicalRouteSnapshot>('/insight/route-table', { revision, rules })
}
export function operateLogicalRouteTable(operation: 'publish' | 'rollback' | 'grants', revision: number) {
  return post<LogicalRouteSnapshot>(`/insight/route-table/${operation}`, { revision })
}
export function replayLogicalRouteTable(revision: number) {
  return post<RouteReplay>('/insight/route-table/replay', { revision })
}
