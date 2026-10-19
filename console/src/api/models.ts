import { downloadText, get, post } from './http'
import type { components, operations } from './schema'

export type ModelGateway = components['schemas']['ModelGateway']
export type ModelGatewayQuery = NonNullable<operations['getModelGateway']['parameters']['query']>

export function getModelGateway(query: ModelGatewayQuery) {
  return get<ModelGateway>('/insight/costs', { params: query })
}

export function updateModelBudget(alias: string, dailyBudgetCny: number, limits?: { rpm: number | null; tpm: number | null; concurrency: number | null }) {
  return post<{ alias: string; dailyBudgetCny: number }>(`/insight/costs/keys/${encodeURIComponent(alias)}/budget`, {
    dailyBudgetCny,
    ...limits,
  })
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
