import { describe, expect, it } from 'vitest'
import { banners, canEdit, canRollback, formatWhen, matchesStatus, promotableEnvs, type PromptDetailModel } from './promptView'

const detail: PromptDetailModel = {
  agent: 'offshore-wind',
  name: 'answer',
  type: 'chat',
  declared: true,
  gate: 'passed',
  labs: { dev: 13, test: 13, staging: 13, prod: 12 },
  states: [
    { id: 'ready', label: '待发布', tone: 'p-acc' },
  ],
  releases: [
    { version: 11, at: '2026-09-10T02:00:00Z', gate: '#197' },
    { version: 12, at: '2026-09-22T02:00:00Z', gate: '#209' },
  ],
  versions: [],
}

describe('prompt page rules', () => {
  it('hides edit and promote on prod and keeps rollback to a released version', () => {
    expect(canEdit('prod')).toBe(false)
    expect(promotableEnvs('prod', 13, detail.labs)).toEqual([])
    expect(canRollback('prod', 11, detail.labs, detail.releases)).toBe(true)
    expect(canRollback('prod', 12, detail.labs, detail.releases)).toBe(false)
    expect(canRollback('prod', 13, detail.labs, detail.releases)).toBe(false)
    expect(canEdit('staging')).toBe(true)
    expect(promotableEnvs('all', 13, detail.labs)).toEqual([])
    expect(promotableEnvs('dev', 12, detail.labs)).toEqual(['dev'])
  })

  it('filters regression failure together with an invalidated run', () => {
    expect(matchesStatus([{ id: 'invalid', label: '回归作废', tone: 'p-bad' }], 'failed')).toBe(true)
    expect(matchesStatus(detail.states, 'ready')).toBe(true)
    expect(matchesStatus(detail.states, 'pending')).toBe(false)
  })

  it('formats today in Shanghai time and shows the prod read-only banner', () => {
    expect(formatWhen('2026-10-07T01:20:00Z', new Date('2026-10-07T02:00:00Z'))).toBe('今天 09:20')
    const notes = banners(detail, 'prod').map((banner) => banner.text)
    expect(notes.some((text) => text.includes('prod 只读'))).toBe(true)
    expect(notes.some((text) => text.includes('待发布') || text.includes('v13'))).toBe(true)
  })
})
