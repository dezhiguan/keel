import type { AgentStatus } from '@/api/agents'
import type { components } from '@/api/schema'

export type StatusTone = 'ok' | 'degraded' | 'failed' | 'idle'
type Risk = components['schemas']['Risk']
type NodeStatus = components['schemas']['NodeStatus']

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

const NODE_STATUS: Record<NodeStatus, { label: string; tone: StatusTone }> = {
  ok: { label: 'ok', tone: 'ok' },
  fallback: { label: '降级', tone: 'degraded' },
  failed: { label: '失败', tone: 'failed' },
}

export function nodeStatus(status: NodeStatus | undefined) {
  return status ? NODE_STATUS[status] : { label: '—', tone: 'idle' as const }
}

export const RISK: Record<Risk, { label: string; cls: string }> = {
  HIGH: { label: '高', cls: 'p-bad' },
  MID: { label: '中', cls: 'p-warn' },
  LOW: { label: '低', cls: 'p-ok' },
}

export function orDash(value: unknown): string {
  return value === null || value === undefined || value === '' ? '—' : String(value)
}

export function fmtN(n: number | null | undefined): string {
  return n === null || n === undefined ? '—' : n.toLocaleString('en-US')
}

export function fmtPct(ratio: number | null | undefined, digits = 1): string {
  return ratio === null || ratio === undefined ? '—' : `${(ratio * 100).toFixed(digits)}%`
}

export function fmtMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—'
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${ms}ms`
}

export function fmtCny(v: number | null | undefined, digits = 4): string {
  return v === null || v === undefined || v === 0 ? '—' : `¥${v.toFixed(digits)}`
}

export function ago(iso: string | null | undefined): string {
  if (!iso) return '—'
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60_000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  if (minutes < 1440) return `${Math.round(minutes / 60)} 小时前`
  const days = Math.round(minutes / 1440)
  return days === 0 ? '今天' : `${days} 天前`
}

export function hms(iso: string | null | undefined): string {
  return iso ? new Date(iso).toTimeString().slice(0, 8) : '—'
}
