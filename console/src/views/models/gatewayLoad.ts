import type { ModelGateway } from '@/api/models'

export type GatewaySection = 'channels' | 'keys' | 'routes'
export type GatewayTab = 'model' | 'key' | 'route'

export function sectionForTab(tab: GatewayTab): GatewaySection {
  if (tab === 'key') return 'keys'
  if (tab === 'route') return 'routes'
  return 'channels'
}

export function prefetchAfter(active: GatewaySection): GatewaySection[] {
  return (['channels', 'keys', 'routes'] as const).filter((section) => section !== active)
}

/** Later responses replace only the fields they actually send. */
export function mergeGateway(base: ModelGateway | null, part: ModelGateway): ModelGateway {
  const next: ModelGateway = { ...(base ?? {}) }
  for (const [key, value] of Object.entries(part)) {
    if (value !== undefined) (next as Record<string, unknown>)[key] = value
  }
  return next
}
