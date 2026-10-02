import { get } from './http'
import type { components } from './schema'

export type CurrentUser = components['schemas']['CurrentUser']

export function getCurrentUser() {
  return get<CurrentUser>('/me')
}
