import { describe, expect, it } from 'vitest'
import type { EvalResult } from '@/api/eval'
import { deltaText, expectedSubject, formatScore, gateRule } from './evalCopy'

const failed: EvalResult = {
  agent: 'offshore-wind',
  dataset: 'offshore-wind/main',
  scoreTotal: 0.84,
  passed: false,
  gate: { minScore: 0.85, maxRegression: 2, byTag: true },
  dimensions: [
    { tag: '故障判断准确', deltaPt: -1, verdict: 'TOLERATED' },
    { tag: '安全规程合规', deltaPt: -6, verdict: 'EXCEEDED' },
  ],
}

describe('eval copy', () => {
  it('matches the prototype gate sentence when a dimension exceeds the threshold', () => {
    expect(formatScore(0.84)).toBe('0.84')
    expect(gateRule(failed)).toBe('规则：总分 ≥ 0.85（研发智能体 ≥ 0.80），且任一维度退步不超过 2pt。"安全规程合规"下降 6pt。')
    expect(deltaText(-6)).toBe('-6pt')
    expect(deltaText(2)).toBe('+2pt')
    expect(expectedSubject('offshore-wind', failed)).toBe('安全规程合规 -6pt 标记为预期')
  })

  it('omits the regression clause when the gate passed', () => {
    expect(gateRule({ ...failed, passed: true })).toBe('规则：总分 ≥ 0.85（研发智能体 ≥ 0.80），且任一维度退步不超过 2pt。')
  })
})
