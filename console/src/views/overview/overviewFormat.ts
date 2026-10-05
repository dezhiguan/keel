export function fmtScore(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return value.toFixed(2)
}

export function fmtMoney(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  if (value === 0) return '¥0.00'
  const digits = Math.abs(value) >= 0.01 ? 2 : 4
  return `¥${value.toFixed(digits)}`
}

export function fmtSeconds(value: number | null | undefined): string {
  if (value === null || value === undefined || value === 0 || Number.isNaN(value)) return '—'
  const rounded = Math.round(value * 10) / 10
  return `${Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1)}s`
}

export function trendText(pct: number | null | undefined): { text: string; tone: 'up' | 'down' | 'flat' } | null {
  if (pct === null || pct === undefined || Number.isNaN(pct)) return null
  if (pct === 0) return { text: '0.0%', tone: 'flat' }
  const up = pct > 0
  return { text: `${up ? '▲' : '▼'} ${Math.abs(pct).toFixed(1)}%`, tone: up ? 'up' : 'down' }
}

export function techLabel(agent: { runtime?: string; language?: string | null; multiAgent?: boolean | null }): string {
  if (agent.runtime === 'dify') return agent.multiAgent ? 'Dify · 多智能体' : 'Dify'
  if (!agent.language) return '—'
  const lang = agent.language.slice(0, 1).toUpperCase() + agent.language.slice(1).toLowerCase()
  return agent.multiAgent ? `${lang} · 多智能体` : lang
}

export function hm(iso?: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toTimeString().slice(0, 5)
}

export const ALERT_KIND: Record<string, [string, string]> = {
  OFFLINE: ['离线', 'p-bad'],
  UNREGISTERED: ['对账', 'p-bad'],
  ZOMBIE: ['无流量', 'p-warn'],
  RETIRE_INCOMPLETE: ['下线', 'p-bad'],
  VERSION_MISMATCH: ['版本', 'p-bad'],
  BUDGET_WARN: ['预算', 'p-warn'],
  GATE_FAILED: ['门禁', 'p-bad'],
  PROMPT_DRIFT: ['提示词', 'p-warn'],
}
