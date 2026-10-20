import { describe, expect, it } from 'vitest'
import { channelPills, channelsForEnv, coverageText, initialDraft, shareBlocked, shareLines } from './channelShare'

const channels = [
  { id: 'm-qwen', name: '百炼·主账号A·生产空间', model: 'qwen3.8-flash', env: 'dev、prod', rpm: null },
  { id: 'm-deepseek', name: 'DeepSeek·官方', model: 'deepseek-flash', env: 'dev、prod', rpm: 500 },
  { id: 'c-dev', name: '百炼·研发空间', model: 'qwen3.8-flash', env: 'dev', rpm: 600 },
]

const routes = [
  { logical: 'chat-default', env: 'prod', primary: 'qwen3.8-flash', secondary: 'deepseek-flash', primaryWeight: 80 },
  { logical: 'chat-default', env: 'dev', primary: 'qwen3.8-flash', secondary: 'deepseek-flash', primaryWeight: 100 },
]

describe('channel share dialog', () => {
  it('lists only channels for the key environment and does not invent rows from old model names', () => {
    expect(channelsForEnv(channels, 'prod').map((channel) => channel.id)).toEqual(['m-qwen', 'm-deepseek'])
    const draft = initialDraft({ models: ['qwen-plus', 'chat-default'], env: 'dev' }, channelsForEnv(channels, 'dev'))
    expect(draft).toEqual({})
    expect(channelPills({ models: ['qwen-plus', 'chat-default'], env: 'dev' }, channels)).toEqual([])
  })

  it('restores a saved share and warns when the route can pick an unselected model', () => {
    const visible = channelsForEnv(channels, 'prod')
    const draft = initialDraft({ env: 'prod', shares: [{ id: 'm-qwen', rpm: 400 }] }, visible)
    expect(draft).toEqual({ 'm-qwen': 400 })
    const coverage = coverageText('prod', routes, draft, visible)
    expect(coverage.tone).toBe('bad')
    expect(coverage.text).toContain('deepseek-flash')
    expect(coverage.detail).toContain('chat-default')
    const lines = shareLines(draft, visible, [{ alias: 'other-prod', shares: [{ id: 'm-qwen', rpm: 520 }] }], 'careermate-prod')
    expect(lines[0].text).toContain('920 / 不限制')
    expect(shareBlocked({ 'm-deepseek': 600 }, visible, [], 'careermate-prod')).toBe(true)
    expect(shareBlocked({ 'm-deepseek': 100 }, visible, [], 'careermate-prod')).toBe(false)
  })
})