export function fmtScore(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return value.toFixed(3).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
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

export type PipelineJob = {
  jobId: string
  title: string
  status?: string
  fixRounds?: number
  maxFixRounds?: number
  needReview?: boolean
  events?: { at?: string | null }[]
}

export type PipelineSource = {
  summary: {
    active: number
    queued: number
    waitingHuman: number
    firstGatePassRate: number
    spentCny: number
  }
  items: PipelineJob[]
}

export type PipelineAlert = { at: string; jobId: string; text: string }

export type PipelineCards = {
  active: number | null
  queued: number | null
  waiting: number | null
  shipped: number | null
  firstPassPct: number | null
  spentCny: number | null
  alerts: PipelineAlert[]
}

const BLANK_PIPELINE: PipelineCards = {
  active: null,
  queued: null,
  waiting: null,
  shipped: null,
  firstPassPct: null,
  spentCny: null,
  alerts: [],
}

export function pipelineMoney(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return `¥${Math.round(value)}`
}

function clockKey(at: string): string {
  return /^\d{2}:\d{2}$/.test(at) ? at : ''
}

function latestClock(job: PipelineJob): string {
  const at = [...(job.events ?? [])].reverse().find((event) => /^\d{2}:\d{2}$/.test(event.at ?? ''))?.at
  return at || '—'
}

export function pipelineCards(source: PipelineSource | null | undefined): PipelineCards {
  if (!source) return BLANK_PIPELINE
  const alerts: PipelineAlert[] = []
  for (const job of source.items) {
    const rounds = job.fixRounds ?? 0
    if (rounds > 0 && job.status !== 'DONE' && job.status !== 'CANCEL') {
      const max = job.maxFixRounds ?? 3
      alerts.push({
        at: latestClock(job),
        jobId: job.jobId,
        text: `${job.jobId} ${job.title}：第 ${rounds} 轮门禁未通过，进入修复（${rounds}/${max}）`,
      })
      continue
    }
    if (job.status === 'WAIT' && job.needReview) {
      alerts.push({
        at: latestClock(job),
        jobId: job.jobId,
        text: `${job.jobId} 等人工 review`,
      })
    }
  }
  alerts.sort((a, b) => clockKey(b.at).localeCompare(clockKey(a.at)))
  return {
    active: source.summary.active,
    queued: source.summary.queued,
    waiting: source.summary.waitingHuman,
    shipped: source.items.filter((job) => job.status === 'DONE').length,
    firstPassPct: Math.round(source.summary.firstGatePassRate * 100),
    spentCny: source.summary.spentCny,
    alerts,
  }
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
