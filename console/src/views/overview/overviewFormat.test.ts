import { describe, expect, it } from 'vitest'
import { fmtMoney, fmtScore, fmtSeconds, techLabel, trendText } from './overviewFormat'

describe('overview format', () => {
  it('rounds a real average score to two decimals', () => {
    expect(fmtScore(0.9083333333333334)).toBe('0.91')
    expect(fmtScore(null)).toBe('—')
  })

  it('keeps a zero cost visible and a missing cost blank', () => {
    expect(fmtMoney(0)).toBe('¥0.00')
    expect(fmtMoney(101.1)).toBe('¥101.10')
    expect(fmtMoney(0.004694)).toBe('¥0.0047')
    expect(fmtMoney(undefined)).toBe('—')
  })

  it('prints latency in seconds and hides an unknown sample', () => {
    expect(fmtSeconds(8.24)).toBe('8.2s')
    expect(fmtSeconds(42)).toBe('42s')
    expect(fmtSeconds(0)).toBe('—')
  })

  it('labels technology from language and delegates', () => {
    expect(techLabel({ language: 'java', multiAgent: true })).toBe('Java · 多智能体')
    expect(techLabel({ runtime: 'dify', language: null })).toBe('Dify')
  })

  it('omits a trend when the previous window is unknown', () => {
    expect(trendText(null)).toBeNull()
    expect(trendText(8.2)).toEqual({ text: '▲ 8.2%', tone: 'up' })
    expect(trendText(-3)).toEqual({ text: '▼ 3.0%', tone: 'down' })
  })
})
