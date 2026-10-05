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
