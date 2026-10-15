export function healthLabel(health?: string | null) {
  if (health === 'LIVE') return '有真实调用'
  if (health === 'PROBED') return '已探测'
  if (health === 'CONFIGURED') return '已配置'
  return '—'
}

export function billStatusLabel(status?: string | null) {
  if (status === 'PENDING') return '待计费'
  if (status === 'FAILED') return '失败'
  return '已结算'
}

export function limitLabel(value?: number | null) {
  return value == null ? '不限制' : String(value)
}

export function priceLabel(version?: string | null, cache?: number | null) {
  const versionText = version ? version : '未标版本'
  const cacheText = cache == null ? '缓存价未单列' : `缓存 ${cache.toFixed(10).replace(/0+$/, '').replace(/\.$/, '')}`
  return `${versionText} · ${cacheText}`
}
