import { describe, expect, it } from 'vitest'
import {
  billStatusLabel,
  capabilityLabel,
  groupLabel,
  healthLabel,
  healthPill,
  limitLabel,
  metricLabel,
  objectiveLabel,
  routeReason,
  thresholdLabel,
  unitPrice,
} from './modelBill'

describe('model bill labels', () => {
  it('names health, bill status and limits the way the models page shows them', () => {
    expect(healthLabel('CONFIGURED')).toBe('已配置')
    expect(healthLabel('LIVE')).toBe('有真实调用')
    expect(healthLabel('PROBED')).toBe('已探测')
    expect(healthPill({ health: 'LIVE', status: 'warn' })).toEqual({ tone: 'degraded', label: '有真实调用 · 波动' })
    expect(healthPill({ health: 'PROBED', status: 'ok' })).toEqual({ tone: 'ok', label: '已探测' })
    expect(healthPill({ drift: true, health: 'LIVE', status: 'ok' })).toEqual({ tone: 'failed', label: '配置漂移' })
    expect(billStatusLabel('PENDING')).toBe('待计费')
    expect(billStatusLabel('FAILED')).toBe('失败')
    expect(routeReason('rejected:LLM_RATE_LIMITED')).toContain('模型未调用')
    expect(routeReason('rejected:LLM_BUDGET_EXCEEDED')).toContain('日预算不足')
    expect(billStatusLabel('SETTLED')).toBe('已结算')
    expect(limitLabel(null)).toBe('不限制')
    expect(limitLabel(60)).toBe('60')
    expect(capabilityLabel('主力', false)).toBe('主力')
    expect(capabilityLabel('向量化（rag-forge）', true)).toBe('向量 · 不降级')
  })

  it('shows input, output and cache prices per million tokens', () => {
    expect(unitPrice(0.0000008, 0.000002, null, '0.0000008/0.000002/')).toEqual({
      prices: '¥0.8 / ¥2.0 / —',
      version: '未标版本',
    })
    expect(unitPrice(0.0000008, 0.000002, 0.00000016, 'p-20261008')).toEqual({
      prices: '¥0.8 / ¥2.0 / ¥0.16',
      version: 'p-20261008',
    })
    expect(unitPrice(null, null, null, '')).toEqual({ prices: '— / — / —', version: '未标版本' })
  })

  it('names route objectives, bill reasons and alert rules', () => {
    expect(objectiveLabel('cost')).toBe('成本优先')
    expect(objectiveLabel(null)).toBe('未接入')
    expect(routeReason('fallback')).toBe('降级')
    expect(routeReason('primary')).toBe('主路由')
    expect(metricLabel('usage_missing')).toBe('待计费条数')
    expect(thresholdLabel('budget', 0.8)).toBe('80%')
    expect(groupLabel('alias')).toBe('智能体')
  })
})

it('labels daily recovery without presenting it as a model attempt', () => {
  expect(routeReason('daily-recovery')).toBe('历史日结算恢复（非调用明细）')
})
