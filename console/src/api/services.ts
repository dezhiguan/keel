import { get } from './http'
import type { components } from './schema'

export type SharedServices = components['schemas']['SharedServices']

export function getSharedServices() {
  return get<SharedServices>('/insight/services')
}
