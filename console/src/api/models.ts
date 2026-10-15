import { downloadText, get, post } from './http'
import type { components, operations } from './schema'

export type ModelGateway = components['schemas']['ModelGateway']
export type ModelGatewayQuery = NonNullable<operations['getModelGateway']['parameters']['query']>

export function getModelGateway(query: ModelGatewayQuery) {
  return get<ModelGateway>('/insight/costs', { params: query })
}

export function updateModelBudget(alias: string, dailyBudgetCny: number) {
  return post<{ alias: string; dailyBudgetCny: number }>(`/insight/costs/keys/${encodeURIComponent(alias)}/budget`, {
    dailyBudgetCny,
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
