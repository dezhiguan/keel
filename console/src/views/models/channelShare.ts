export type ShareChannel = {
  id: string
  name?: string
  model?: string
  env?: string
  rpm?: number | null
}

export type ShareKey = {
  alias?: string
  env?: string | null
  models?: string[]
  rpm?: number | null
  shares?: { id?: string; rpm?: number }[]
}

export type ShareRoute = {
  logical?: string
  env?: string
  primary?: string
  secondary?: string
  primaryWeight?: number
}

export type ChannelDraft = Record<string, number | ''>

export function channelsForEnv(channels: ShareChannel[], env?: string | null) {
  if (!env) return []
  return channels.filter((channel) => (channel.env || '').split('、').filter(Boolean).includes(env))
}

export function initialDraft(key: ShareKey, channels: ShareChannel[]): ChannelDraft {
  const draft: ChannelDraft = {}
  const shares = key.shares ?? []
  if (shares.length) {
    for (const share of shares) {
      if (share.id && channels.some((channel) => channel.id === share.id)) draft[share.id] = share.rpm ?? ''
    }
    return draft
  }
  for (const channel of channels) {
    if (channel.model && (key.models ?? []).includes(channel.model)) draft[channel.id] = ''
  }
  return draft
}

export function routeTargets(row: ShareRoute) {
  const weight = row.primaryWeight ?? 0
  return [weight > 0 ? row.primary : '', weight < 100 ? row.secondary : ''].filter((name): name is string => !!name)
}

export function routesForEnv(routes: ShareRoute[], env?: string | null) {
  if (!env) return []
  return routes.filter((row) => (row.env === 'prod' ? env === 'prod' : row.env === 'dev' ? env !== 'prod' : row.env === env))
}

export function neededModels(routes: ShareRoute[], env?: string | null) {
  const names = new Set<string>()
  for (const row of routesForEnv(routes, env)) routeTargets(row).forEach((name) => names.add(name))
  return [...names]
}

export function selectedModels(draft: ChannelDraft, channels: ShareChannel[]) {
  const names = new Set<string>()
  for (const id of Object.keys(draft)) {
    const model = channels.find((channel) => channel.id === id)?.model
    if (model) names.add(model)
  }
  return [...names]
}

export function coverageText(env: string | null | undefined, routes: ShareRoute[], draft: ChannelDraft, channels: ShareChannel[]) {
  const needed = neededModels(routes, env)
  const have = selectedModels(draft, channels)
  const miss = needed.filter((model) => !have.includes(model))
  const uses = routesForEnv(routes, env).map((row) => `${row.logical}（${routeTargets(row).join(' / ')}）`).join('、')
  if (miss.length) {
    return {
      tone: 'bad' as const,
      text: `路由表会给 ${env} 选到 ${miss.join('、')}，本 Key 没有允许该模型的任何渠道，选中后请求发不出去。`,
      detail: uses ? `涉及：${uses}` : '',
    }
  }
  return {
    tone: 'ok' as const,
    text: `已覆盖路由表可能选到的模型：${needed.join('、') || '无'}。`,
    detail: '',
  }
}

export function shareLines(draft: ChannelDraft, channels: ShareChannel[], keys: ShareKey[], alias?: string) {
  return Object.keys(draft).map((id) => {
    const channel = channels.find((item) => item.id === id)
    const mine = Number(draft[id] || 0)
    const others = keys
      .filter((key) => key.alias !== alias)
      .reduce((sum, key) => sum + (key.shares?.find((share) => share.id === id)?.rpm ?? 0), 0)
    const total = mine + others
    const limit = channel?.rpm
    const over = limit != null && total > limit
    return {
      id,
      name: channel?.name || id,
      total,
      limit,
      over,
      text: limit == null
        ? `${channel?.name || id}：每分钟请求数（RPM）合计 ${total} / 不限制`
        : `${channel?.name || id}：每分钟请求数（RPM）合计 ${total} / ${limit}${over ? ` 超出 ${total - limit}` : ` · 剩余 ${limit - total}`}`,
    }
  })
}

export function shareBlocked(draft: ChannelDraft, channels: ShareChannel[], keys: ShareKey[], alias?: string) {
  const ids = Object.keys(draft)
  if (!ids.length) return true
  if (ids.some((id) => draft[id] === '' || !Number.isInteger(draft[id]) || Number(draft[id]) < 1)) return true
  return shareLines(draft, channels, keys, alias).some((line) => line.over)
}

export function channelPills(key: ShareKey, channels: ShareChannel[]) {
  const mine = channelsForEnv(channels, key.env)
  const draft = initialDraft(key, mine)
  return Object.keys(draft).map((id) => {
    const channel = mine.find((item) => item.id === id)
    const rpm = draft[id]
    const name = channel?.name || channel?.model || id
    return rpm === '' ? name : `${name} · ${rpm}`
  })
}
