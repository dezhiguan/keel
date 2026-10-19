import { describe, expect, it } from 'vitest'
import { breakerLabel } from './channelBreaker'

describe('breakerLabel', () => {
  const now = 1_700_000_000_000

  it('stays closed until two failures open it', () => {
    expect(breakerLabel('closed', 0, now)).toMatchObject({ label: '正常', action: 'trip' })
  })

  it('counts down while the channel is open', () => {
    expect(breakerLabel('open', now + 12_500, now)).toMatchObject({ label: '熔断中', remain: 13, action: 'wait' })
  })

  it('asks for one probe after the cool-down', () => {
    expect(breakerLabel('open', now - 1, now)).toMatchObject({ label: '半开 · 待探测', action: 'probe' })
    expect(breakerLabel('half_open', now - 1, now).tone).toBe('p-warn')
  })
})
