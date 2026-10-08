import { describe, expect, it } from 'vitest'
import { fmtMoney, fmtScore, fmtSeconds, pipelineCards, pipelineMoney, techLabel, trendText } from './overviewFormat'

describe('overview format', () => {
  it('keeps the eval score digits the eval page shows', () => {
    expect(fmtScore(0.915)).toBe('0.915')
    expect(fmtScore(0.9083333333333334)).toBe('0.908')
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

  it('keeps pipeline cards blank until the job ledger answers', () => {
    expect(pipelineCards(null)).toEqual({
      active: null,
      queued: null,
      waiting: null,
      shipped: null,
      firstPassPct: null,
      spentCny: null,
      alerts: [],
    })
    expect(pipelineMoney(null)).toBe('—')
  })

  it('fills pipeline cards from the board and flags gate and review jobs', () => {
    const cards = pipelineCards({
      summary: { active: 8, queued: 2, waitingHuman: 4, firstGatePassRate: 0.54, spentCny: 227.6 },
      items: [
        { jobId: 'DF-0021', title: '升级 dev-agent', status: 'WAIT', needReview: true, events: [{ at: '10:53' }] },
        { jobId: 'DF-0019', title: '发布说明生成', status: 'RUN', fixRounds: 1, maxFixRounds: 3, events: [{ at: '17:41' }] },
        { jobId: 'DF-0012', title: '工单分派', status: 'DONE' },
        { jobId: 'DF-0013', title: '生产 ci-doctor', status: 'DONE' },
        { jobId: 'DF-0011', title: 'FAQ 同步', status: 'CANCEL', fixRounds: 1 },
      ],
    })
    expect(cards.active).toBe(8)
    expect(cards.queued).toBe(2)
    expect(cards.waiting).toBe(4)
    expect(cards.shipped).toBe(2)
    expect(cards.firstPassPct).toBe(54)
    expect(pipelineMoney(cards.spentCny)).toBe('¥228')
    expect(cards.alerts).toEqual([
      { at: '17:41', jobId: 'DF-0019', text: 'DF-0019 发布说明生成：第 1 轮门禁未通过，进入修复（1/3）' },
      { at: '10:53', jobId: 'DF-0021', text: 'DF-0021 等人工 review' },
    ])
  })

  it('omits a trend when the previous window is unknown', () => {
    expect(trendText(null)).toBeNull()
    expect(trendText(8.2)).toEqual({ text: '▲ 8.2%', tone: 'up' })
    expect(trendText(-3)).toEqual({ text: '▼ 3.0%', tone: 'down' })
  })
})
