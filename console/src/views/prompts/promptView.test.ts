import { describe, expect, it } from 'vitest'
import { banners, canEdit, canRollback, configPayload, draftError, editingDrafts, formatWhen, matchesStatus, promotableEnvs, removedConstraintMessage, removedConstraints, unchangedPrompt, type PromptDetailModel } from './promptView'

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

  it('starts a chat draft when there is no version and rejects a bad config', () => {
    const drafts = editingDrafts('chat', null)
    expect(drafts.map((draft) => draft.role)).toEqual(['system', 'user'])
    expect(drafts[1]?.content).toContain('{{question}}')
    expect(draftError('chat', drafts)).toBe('')
    expect(draftError('chat', [{ role: 'tool', content: 'x' }])).toContain('角色')
    expect(draftError('text', [{ role: 'text', content: '  ' }])).toContain('不能为空')
    expect(editingDrafts('text', '已有正文')[0]?.content).toBe('已有正文')
    expect('error' in configPayload({ temperature: '3', maxTokens: '', topP: '' })).toBe(true)
    expect('error' in configPayload({ temperature: '0.2', maxTokens: '0', topP: '' })).toBe(true)
    expect(configPayload({ temperature: '0.2', maxTokens: '800', topP: '' })).toEqual({ config: { temperature: 0.2, max_tokens: 800 } })
    const next = [{ role: 'user', content: '{{question}}' }]
    expect(unchangedPrompt(next, { temperature: 0.2 }, next, { temperature: 0.2 })).toBe(true)
    expect(unchangedPrompt(null, {}, next, {})).toBe(false)
  })

  it('names the eval dimension when a constraint is removed', () => {
    const message = removedConstraintMessage(removedConstraints(
      [{ kind: 'constraint', id: 'c-1', text: '不要编造', dimension: '拒答 / 无法确认' }, { kind: 'role', text: '值班员' }],
      [{ kind: 'role', text: '值班员' }],
    ))
    expect(message).toContain('不要编造')
    expect(message).toContain('拒答 / 无法确认')
    expect(message).toContain('staging 回归')
    expect(removedConstraints([{ kind: 'role', text: '值班员' }], [])).toEqual([])
  })
})
