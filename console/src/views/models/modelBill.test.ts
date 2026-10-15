import { describe, expect, it } from 'vitest'
import { billStatusLabel, healthLabel, limitLabel, priceLabel } from './modelBill'

describe('model bill labels', () => {
  it('names health, bill status and limits the way the models page shows them', () => {
    expect(healthLabel('CONFIGURED')).toBe('已配置')
    expect(healthLabel('LIVE')).toBe('有真实调用')
    expect(healthLabel('PROBED')).toBe('已探测')
    expect(billStatusLabel('PENDING')).toBe('待计费')
    expect(billStatusLabel('FAILED')).toBe('失败')
    expect(billStatusLabel('SETTLED')).toBe('已结算')
    expect(limitLabel(null)).toBe('不限制')
    expect(limitLabel(60)).toBe('60')
    expect(priceLabel('2026-10-08', 0.0000001)).toBe('2026-10-08 · 缓存 0.0000001')
    expect(priceLabel('', null)).toBe('未标版本 · 缓存价未单列')
  })
})
