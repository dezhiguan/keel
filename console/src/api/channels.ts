import { del, get, post } from './http'

export interface ChannelRow {
  id: number
  kind: 'HOSTED' | 'EMBED' | 'WEB' | 'MINIPROGRAM'
  identity: 'EXCHANGE' | 'OPENID' | 'ANONYMOUS'
  origin?: string | null
  quotaPerHour?: number | null
  status?: 'ONLINE' | 'REGISTERED' | 'REVOKED'
  credential?: string | null
  hostedPath?: string
  embed?: string | null
}

export function listChannels(agent: string) {
  return get<{ items?: ChannelRow[] }>(`/agents/${encodeURIComponent(agent)}/channels`)
}

export function publishChannel(agent: string, body: {
  kind: ChannelRow['kind']
  identity: ChannelRow['identity']
  origin?: string
  quotaPerHour?: number
}) {
  return post<ChannelRow>(`/agents/${encodeURIComponent(agent)}/channels`, body)
}

export function revokeChannel(agent: string, id: number) {
  return del<{ id: number; status: 'REVOKED' }>(`/agents/${encodeURIComponent(agent)}/channels/${id}`)
}
