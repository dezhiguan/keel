import type { StatusTone } from '@/utils/format'

export function healthLabel(health?: string | null) {
  if (health === 'LIVE') return '有真实调用'
  if (health === 'PROBED') return '已探测'
  if (health === 'CONFIGURED') return '已配置'
  return '—'
}

export function healthPill(model: {
  health?: string | null
  status?: string | null
  drift?: boolean | null
  priceConfigured?: boolean | null
}): { tone: StatusTone; label: string } {
  if (model.priceConfigured === false) return { tone: 'failed', label: '未配单价' }
  if (model.drift) return { tone: 'failed', label: '配置漂移' }
  const name = healthLabel(model.health)
  const base = name === '—' ? '' : name
  if (model.status === 'bad') return { tone: 'failed', label: base ? `${base} · 异常` : '异常' }
  if (model.status === 'warn') return { tone: 'degraded', label: base ? `${base} · 波动` : '波动' }
  if (!base) return { tone: 'idle', label: '—' }
  return { tone: 'ok', label: base }
}

export function capabilityLabel(role?: string | null, embedding?: boolean | null) {
  if (embedding || (role ?? '').startsWith('向量')) return '向量 · 不降级'
  return role || '—'
}

export function billStatusLabel(status?: string | null) {
  if (status === 'PENDING') return '待计费'
  if (status === 'FAILED') return '失败'
  return '已结算'
}

export function limitLabel(value?: number | null) {
  return value == null ? '不限制' : String(value)
}

const VERSION_PRICE = /^([0-9]*\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\/([0-9]*\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\/(.*)$/

export function unitPrice(
  input?: number | null,
  output?: number | null,
  cache?: number | null,
  version?: string | null,
) {
  const parsed = parseVersionPrice(version)
  const inPrice = input ?? parsed?.input ?? null
  const outPrice = output ?? parsed?.output ?? null
  const cachePrice = cache ?? parsed?.cache ?? null
  return {
    prices: `${yuanPerMillion(inPrice)} / ${yuanPerMillion(outPrice)} / ${yuanPerMillion(cachePrice)}`,
    version: parsed || !version ? '未标版本' : version,
  }
}

export function yuanPerMillion(perToken?: number | null) {
  if (perToken == null || !Number.isFinite(perToken) || perToken <= 0) return '—'
  const yuan = Math.round(perToken * 1_000_000 * 10_000) / 10_000
  const text = yuan.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
  return `¥${text.includes('.') ? text : `${text}.0`}`
}

export function objectiveLabel(objective?: string | null) {
  if (objective === 'cost') return '成本优先'
  if (objective === 'latency') return '延迟优先'
  if (objective === 'balanced') return '均衡'
  return '未接入'
}

export function routeReason(routeVersion?: string | null) {
  if (routeVersion === 'fallback') return '降级'
  if (routeVersion === 'primary') return '主路由'
  return routeVersion || '—'
}

export function metricLabel(metric?: string | null) {
  if (metric === 'budget') return '已结算 + 预留 / 日预算'
  if (metric === 'usage_missing') return '待计费条数'
  if (metric === 'channel_failure') return '错误率'
  return metric || '—'
}

export function thresholdLabel(metric?: string | null, threshold?: number | null) {
  if (threshold == null || Number.isNaN(threshold)) return '—'
  if ((metric === 'budget' || metric === 'channel_failure') && threshold > 0 && threshold <= 1) {
    return `${Math.round(threshold * 100)}%`
  }
  return String(threshold)
}

export function groupLabel(group?: string | null) {
  if (group === 'alias' || group === 'agent') return '智能体'
  if (group === 'model') return '模型'
  if (group === 'channel') return '渠道'
  if (group === 'env') return '环境'
  return group || '—'
}

export function billClock(ts?: string | null) {
  if (!ts) return '—'
  const date = new Date(ts)
  if (Number.isNaN(date.getTime())) return ts
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function parseVersionPrice(version?: string | null) {
  const match = VERSION_PRICE.exec(version ?? '')
  if (!match) return null
  const cache = match[3] === '' ? null : Number(match[3])
  return {
    input: Number(match[1]),
    output: Number(match[2]),
    cache: cache != null && Number.isFinite(cache) ? cache : null,
  }
}
