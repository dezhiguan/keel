import type { AgentStatus } from '@/api/agents'

export type StatusTone = 'ok' | 'degraded' | 'failed' | 'idle'

const AGENT_STATUS: Record<AgentStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'idle' },
  REGISTERED: { label: '已注册', tone: 'idle' },
  ONLINE: { label: '在线', tone: 'ok' },
  DEGRADED: { label: '降级', tone: 'degraded' },
  OFFLINE: { label: '离线', tone: 'failed' },
  RETIRED: { label: '已下线', tone: 'idle' },
}

export function agentStatus(status: AgentStatus | undefined) {
  return status ? AGENT_STATUS[status] : { label: '—', tone: 'idle' as const }
}

export function orDash(value: unknown): string {
  return value === null || value === undefined || value === '' ? '—' : String(value)
}
