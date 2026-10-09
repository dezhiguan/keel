export function eventTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date)
  const get = (type: Intl.DateTimeFormatPartTypes) => parts.find((part) => part.type === type)?.value ?? ''
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')}:${get('second')}`
}

export function shortHash(value: string | null | undefined): string {
  if (!value) return '—'
  const hex = value.startsWith('sha256:') ? value.slice('sha256:'.length) : value
  if (!hex) return '—'
  return `sha256:${hex.slice(0, 8)}…`
}

export function payloadText(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2)
}

export const EVENT_KINDS = [
  { value: 'devflow.stage', label: '研发任务阶段变化' },
  { value: 'devflow.takeover', label: '人工接管 / 交还' },
  { value: 'drift', label: '提示词漂移' },
] as const

export function eventKind(payload: unknown): string {
  if (!payload || typeof payload !== 'object') return ''
  const kind = (payload as { kind?: unknown }).kind
  return typeof kind === 'string' ? kind : ''
}

/** TODO(DF-2): 服务端已按 kind 过滤。这里再收一次当前页，避免旧接口把别的事件混进来。 */
export function filterAuditPage<T extends { action?: string | null; payload?: unknown }>(
  items: T[],
  action: string | undefined,
  kind: string | undefined,
): T[] {
  return items.filter((item) => {
    if (action && item.action !== action) return false
    if (action === 'config.change' && kind && eventKind(item.payload) !== kind) return false
    return true
  })
}
