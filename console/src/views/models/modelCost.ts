/** 模型与成本提案页的演示规则，与 docs/console/Keel-优化效果-20261018.html 一致。 */

export const MODELS = ['qwen3.8-flash', 'deepseek-flash'] as const

export type Channel = {
  n: string
  m: string
  env: string
  rpm: number
  use: number
  batch?: boolean
  br?: { until: number } | null
}

export type ModelKey = {
  a: string
  env: string
  sh: Record<number, number>
  tpm: number
  c: number
}

export type RouteRow = {
  l: string
  c: string
  env: string
  w: number
  fb: string
  g: string
}

export type ModelCostState = {
  channels: Channel[]
  keys: ModelKey[]
  ver: number
  hist: RouteRow[][]
  rows: RouteRow[]
}

export type ShareLine = {
  name: string
  total: number
  limit: number
  over: boolean
}

export type ShareReport = {
  empty: boolean
  over: boolean
  lines: ShareLine[]
  missing: string[]
  covered: string[]
  uses: string
}

export type Gap = { key: ModelKey; model: string }

export type RejectView = {
  alias: string
  tone: 'p-warn' | 'p-ok' | 'p-bad'
  label: string
  reason: string
}

export type ReplayView = {
  qwen: number
  deepseek: number
  cost: string
  delta: string
  deltaUp: boolean
  errors: number
  lost: number
}

export function createModelCostState(): ModelCostState {
  return {
    channels: [
      { n: '百炼·主账号A·生产空间', m: 'qwen3.8-flash', env: 'prod', rpm: 1200, use: 62 },
      { n: '百炼·主账号A·研发空间', m: 'qwen3.8-flash', env: 'dev', rpm: 600, use: 18 },
      { n: 'DeepSeek·官方', m: 'deepseek-flash', env: 'prod', rpm: 500, use: 71 },
      { n: '百炼·Batch', m: 'qwen3.8-flash', env: '非实时', rpm: 0, use: 0, batch: true },
    ],
    keys: [
      { a: 'careermate-prod', env: 'prod', sh: { 0: 400 }, tpm: 300000, c: 20 },
      { a: 'cs-bot-prod', env: 'prod', sh: { 0: 420, 2: 100 }, tpm: 300000, c: 20 },
      { a: 'askdb-prod', env: 'prod', sh: { 2: 300 }, tpm: 200000, c: 10 },
      { a: 'offshore-wind-prod', env: 'prod', sh: { 2: 120, 0: 100 }, tpm: 150000, c: 10 },
      { a: 'careermate-dev', env: 'dev', sh: { 1: 120 }, tpm: 80000, c: 5 },
      { a: 'cs-bot-dev', env: 'dev', sh: { 1: 120 }, tpm: 80000, c: 5 },
    ],
    ver: 7,
    hist: [],
    rows: [
      { l: 'chat-default', c: 'env=prod', env: 'prod', w: 80, fb: 'deepseek-flash', g: '—' },
      { l: 'chat-default', c: 'env≠prod', env: 'dev', w: 100, fb: '无', g: '—' },
      { l: 'chat-cheap', c: 'env=prod', env: 'prod', w: 30, fb: '按延迟', g: '新规则 10%' },
      { l: 'long-context', c: 'env=prod · 输入 > 200k', env: 'prod', w: 0, fb: 'qwen3.8-flash', g: '—' },
    ],
  }
}

export function channelHost(name: string) {
  if (name.startsWith('百炼')) return 'dashscope.aliyuncs.com'
  if (name.startsWith('DeepSeek')) return 'api.deepseek.com'
  return '—'
}

export function channelShort(name: string) {
  return name.replace('·主账号A', '')
}

export function allocated(keys: ModelKey[], index: number) {
  return keys.reduce((sum, key) => sum + (key.sh[index] || 0), 0)
}

export function allocationPercent(used: number, cap: number) {
  return Math.round((used / cap) * 100)
}

export function allocationTone(percent: number) {
  if (percent > 100) return { label: '超分配，需提额', tone: 'p-bad', color: 'var(--bad)' }
  if (percent > 85) return { label: '接近上限', tone: 'p-warn', color: 'var(--warn)' }
  return { label: '正常', tone: 'p-ok', color: 'var(--soft)' }
}

export function channelIndexes(key: ModelKey) {
  return Object.keys(key.sh).map(Number)
}

export function keyModels(key: ModelKey, channels: Channel[]) {
  return [...new Set(channelIndexes(key).map((index) => channels[index]?.m).filter(Boolean))]
}

export function routeTargets(row: RouteRow) {
  return [row.w > 0 ? 'qwen3.8-flash' : '', row.w < 100 ? 'deepseek-flash' : ''].filter(Boolean)
}

export function neededModels(key: ModelKey, rows: RouteRow[]) {
  const need = new Set<string>()
  rows.filter((row) => row.env === key.env).forEach((row) => {
    routeTargets(row).forEach((model) => need.add(model))
  })
  return [...need]
}

export function routeGaps(row: RouteRow, keys: ModelKey[], channels: Channel[]): Gap[] {
  const miss: Gap[] = []
  const targets = routeTargets(row)
  keys.filter((key) => key.env === row.env).forEach((key) => {
    const have = keyModels(key, channels)
    targets.forEach((model) => {
      if (!have.includes(model)) miss.push({ key, model })
    })
  })
  return miss
}

export function uniqueGaps(rows: RouteRow[], keys: ModelKey[], channels: Channel[]) {
  const all = rows.flatMap((row) => routeGaps(row, keys, channels))
  return [...new Map(all.map((gap) => [gap.key.a + gap.model, gap])).values()]
}

export function missingModels(key: ModelKey, channels: Channel[], rows: RouteRow[]) {
  const have = keyModels(key, channels)
  return neededModels(key, rows).filter((model) => !have.includes(model))
}

export function breakerView(channel: Channel, now: number) {
  if (!channel.br?.until) return { tone: 'p-ok', label: '正常', remain: '' }
  if (channel.br.until > now) {
    return { tone: 'p-bad', label: '熔断中', remain: `${Math.ceil((channel.br.until - now) / 1000)}s` }
  }
  return { tone: 'p-warn', label: '半开 · 待探测', remain: '' }
}

export function tripChannel(channel: Channel, now: number) {
  channel.br = { until: now + 10_000 }
}

export function probeChannel(channel: Channel) {
  channel.br = null
}

export function inspectShare(
  key: ModelKey,
  draft: Record<number, number>,
  keys: ModelKey[],
  channels: Channel[],
  rows: RouteRow[],
): ShareReport {
  const lines: ShareLine[] = []
  let over = false
  Object.keys(draft).map(Number).forEach((index) => {
    const total = allocated(keys, index) - (key.sh[index] || 0) + (draft[index] || 0)
    const limit = channels[index].rpm
    if (total > limit) over = true
    lines.push({ name: channels[index].n, total, limit, over: total > limit })
  })
  const have = [...new Set(Object.keys(draft).map((index) => channels[Number(index)].m))]
  const covered = neededModels(key, rows)
  const missing = covered.filter((model) => !have.includes(model))
  const uses = rows
    .filter((row) => row.env === key.env)
    .map((row) => `${row.l}（${routeTargets(row).join(' / ')}）`)
    .join('、')
  return { empty: Object.keys(draft).length === 0, over, lines, missing, covered, uses }
}

export function prodQwenChannels(channels: Channel[]) {
  return channels
    .map((channel, index) => ({ channel, index }))
    .filter((item) => item.channel.env === 'prod' && item.channel.m === 'qwen3.8-flash')
}

export function insertChannel(channels: Channel[], input: { n: string; m: string; env: string; rpm: number }) {
  const env = input.env === 'prod' ? 'prod' : 'dev'
  channels.splice(channels.length - 1, 0, { n: input.n, m: input.m, env, rpm: input.rpm, use: 0 })
}

export function switchProdQwen(keys: ModelKey[], channels: Channel[], to: number) {
  let moved = 0
  keys.filter((key) => key.env === 'prod').forEach((key) => {
    channelIndexes(key).forEach((index) => {
      if (index !== to && channels[index].m === channels[to].m) {
        key.sh[to] = (key.sh[to] || 0) + key.sh[index]
        delete key.sh[index]
        moved += 1
      }
    })
  })
  return moved
}

export function buildReject(keys: ModelKey[], channels: Channel[], keyIndex: number, vendor: boolean, now: number): RejectView {
  const key = keys[keyIndex]
  const fromIndex = channelIndexes(key)[0]
  const from = channels[fromIndex]
  const usable = (index: number) => index !== fromIndex
    && !channels[index].batch
    && channels[index].m === from.m
    && channels[index].env === key.env
    && breakerView(channels[index], now).label === '正常'
    && allocated(keys, index) < channels[index].rpm
  const alternate = channelIndexes(key).find(usable)
  const outside = channels.findIndex((_channel, index) => usable(index) && !(index in key.sh))
  if (!vendor) {
    return {
      alias: key.a,
      tone: 'p-warn',
      label: '网关拒绝 429',
      reason: `超出 Key 在「${from.n}」的份额 RPM ${key.sh[fromIndex]}，未打到厂商，不换渠道`,
    }
  }
  if (alternate !== undefined) {
    return {
      alias: key.a,
      tone: 'p-ok',
      label: '厂商 429 → 已换渠道',
      reason: `「${from.n}」返回 429（Retry-After 20s，期间跳过）→ 改打本 Key 允许的同模型「${channels[alternate].n}」成功，计 1 次切换`,
    }
  }
  if (outside >= 0) {
    return {
      alias: key.a,
      tone: 'p-bad',
      label: '厂商限流 429',
      reason: `「${from.n}」达到厂商上限；同模型的「${channels[outside].n}」有余量，但本 Key 没有允许这个渠道，不能换。到 Key 与配额里勾选它`,
    }
  }
  return {
    alias: key.a,
    tone: 'p-bad',
    label: '厂商限流 429',
    reason: `「${from.n}」达到厂商上限；同模型同环境没有可用渠道，返回 429 并记渠道告警。可「新增渠道」加一个生产账号`,
  }
}

/** 优化稿只在前 4 把 Key 里随机，Math.random() 不到 1。 */
export function simulatedKeyIndex(random: number, length: number) {
  return Math.min(length - 1, Math.floor(random * 4))
}

export function fixCoverage(keys: ModelKey[], channels: Channel[], rows: RouteRow[]) {
  const fails: string[] = []
  let added = 0
  uniqueGaps(rows, keys, channels).forEach(({ key, model }) => {
    const index = channels.findIndex((channel, cursor) => !channel.batch
      && channel.m === model
      && channel.env === key.env
      && allocated(keys, cursor) + 50 <= channel.rpm)
    if (index < 0) {
      fails.push(`${key.a}（${key.env} 没有 ${model} 渠道或已满）`)
      return
    }
    key.sh[index] = 50
    added += 1
  })
  return { added, fails }
}

export function replayDraft(weight: number, gapKeyCount: number): ReplayView {
  const ratio = weight / 100
  const base = 0.8 * 0.8 + 0.2 * 1.27
  const next = ratio * 0.8 + (1 - ratio) * 1.27
  const change = next / base
  const delta = ((change - 1) * 100).toFixed(1)
  return {
    qwen: Math.round(ratio * 100),
    deepseek: Math.round((1 - ratio) * 100),
    cost: (41.2 * change).toFixed(2),
    delta,
    deltaUp: Number(delta) > 0,
    errors: Math.max(0, Math.round((ratio - 0.7) * 370)),
    lost: gapKeyCount ? Math.round(12480 * 0.6 * (1 - ratio) * gapKeyCount / 4) : 0,
  }
}

export function replayGapKeys(row: RouteRow, keys: ModelKey[], channels: Channel[]) {
  const gaps = routeGaps(row, keys, channels)
  return gaps.length ? new Set(gaps.map((gap) => gap.key.a)).size : 0
}

export function publishRoute(state: ModelCostState) {
  const gaps = state.rows.flatMap((row) => routeGaps(row, state.keys, state.channels))
  if (gaps.length) return { ok: false as const, keys: new Set(gaps.map((gap) => gap.key.a)).size }
  state.hist.push(JSON.parse(JSON.stringify(state.rows)) as RouteRow[])
  state.ver += 1
  return { ok: true as const, version: state.ver }
}

export function rollbackRoute(state: ModelCostState) {
  const previous = state.hist.pop()
  if (!previous) return false
  state.rows = previous
  state.ver -= 1
  return true
}
