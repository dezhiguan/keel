export type BreakerAction = 'trip' | 'probe' | 'wait'

export function breakerLabel(state: string | undefined, openUntil: number | undefined, now: number) {
  const until = openUntil ?? 0
  if (until > now) {
    return { tone: 'p-bad', label: '熔断中', remain: Math.max(1, Math.ceil((until - now) / 1000)), action: 'wait' as BreakerAction }
  }
  if (state === 'half_open' || until > 0) {
    return { tone: 'p-warn', label: '半开 · 待探测', remain: 0, action: 'probe' as BreakerAction }
  }
  return { tone: 'p-ok', label: '正常', remain: 0, action: 'trip' as BreakerAction }
}
