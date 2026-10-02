import { get } from './http'
import type { components, operations } from './schema'

export type ModelGateway = components['schemas']['ModelGateway']
export type ModelGatewayQuery = NonNullable<operations['getModelGateway']['parameters']['query']>

export function getModelGateway(query: ModelGatewayQuery) {
  return get<ModelGateway>('/insight/costs', { params: query })
}
