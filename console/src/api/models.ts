import { get, post } from './http'
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
