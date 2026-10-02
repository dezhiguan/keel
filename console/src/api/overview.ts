import { get } from './http'
import type { components, operations } from './schema'

export type Overview = components['schemas']['Overview']
export type OverviewQuery = NonNullable<operations['getOverview']['parameters']['query']>

export function getOverview(query: OverviewQuery) {
  return get<Overview>('/insight/overview', { params: query })
}
