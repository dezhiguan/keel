import { get } from './http'
import type { components } from './schema'

export type SharedServices = components['schemas']['SharedServices']

export function getSharedServices(env: string = 'all', signal?: AbortSignal) {
  return get<SharedServices>('/insight/services', { params: { env }, signal, timeout: 25000 })
}
