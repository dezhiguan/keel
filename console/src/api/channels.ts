import { get, post } from './http'

export interface ChannelRow {
  id: number
  kind: 'HOSTED' | 'EMBED' | 'WEB' | 'MINIPROGRAM'
  identity: 'EXCHANGE' | 'OPENID' | 'ANONYMOUS'
  origin?: string | null
  quotaPerHour?: number | null
  status?: string
  credential?: string
  hostedPath?: string
  embed?: string
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
