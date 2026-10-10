import { describe, expect, it } from 'vitest'
import { describeCron, previewBlocked, previewMode, previewWarning, stepRows } from './preview'

describe('agent preview', () => {
  it('falls back to chat when the manifest does not declare a mode', () => {
    expect(previewMode(null)).toBe('chat')
    expect(previewMode({})).toBe('chat')
    expect(previewMode({ interaction: {} })).toBe('chat')
    expect(previewMode({ interaction: { mode: 'schedule', schedule: '0 2 * * *' } })).toBe('schedule')
    expect(previewMode({ interaction: { mode: 'unknown' as never } })).toBe('chat')
  })

  it('blocks drafts and retired agents but only warns when offline', () => {
    expect(previewBlocked('DRAFT')).toContain('还没注册')
    expect(previewBlocked('RETIRED')).toContain('已下线')
    expect(previewBlocked('ONLINE')).toBeNull()
    expect(previewBlocked('OFFLINE')).toBeNull()
    expect(previewWarning('OFFLINE')).toContain('离线')
    expect(previewWarning('ONLINE')).toBeNull()
  })

  it('reads common cron shapes and leaves the rest raw', () => {
    expect(describeCron('0 2 * * *')).toBe('每天 02:00')
    expect(describeCron('30 9 * * 1-5')).toBe('工作日 09:30')
    expect(describeCron('0 8 * * 1')).toBe('每周一 08:00')
    expect(describeCron('0 8 1 * *')).toBe('每月 1 日 08:00')
    expect(describeCron('*/15 * * * *')).toBe('每 15 分钟')
    expect(describeCron('5 */2 * * *')).toBe('每 2 小时的第 5 分')
    expect(describeCron('0 9-18 * * *')).toBeNull()
    expect(describeCron('0 2 * *')).toBeNull()
    expect(describeCron(undefined)).toBeNull()
  })

  it('orders trace nodes as steps without the root run', () => {
    const rows = stepRows([
      { id: 'root', name: 'offshore-wind', type: 'agent', depth: 0, startMs: 0 },
      { id: 'b', name: 'llm.answer', shortName: '生成建议', type: 'generation', depth: 1, startMs: 900, durationMs: 2100, outputSummary: '建议先检查变桨' },
      { id: 'a', name: 'rag.fault-manual', type: 'retriever', depth: 1, startMs: 100, durationMs: 640, outputSummary: 'rag.fault-manual', status: 'fallback' },
      { id: 'c', name: 'alarm_query', type: 'tool', depth: 2, startMs: 120 },
    ])
    expect(rows.map((row) => row.id)).toEqual(['a', 'c', 'b'])
    expect(rows[0]).toMatchObject({ name: 'rag.fault-manual', type: '检索', depth: 0, status: 'fallback', output: '' })
    expect(rows[1]).toMatchObject({ type: '工具', depth: 1, durationMs: null, status: 'ok' })
    expect(rows[2]).toMatchObject({ name: '生成建议', type: '模型', output: '建议先检查变桨' })
  })
})
