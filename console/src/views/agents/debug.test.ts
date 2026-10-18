import { describe, expect, it } from 'vitest'
import {
  appendEvents, byRecent, debugBlocked, debugMode, debugStoreKey, debugWarning, describeCron,
  describeLiveFrame, pageStatus, parseSessions, pruneSessions, sessionTitle, stepRows, takeSseFrames,
  traceEvent, visibleHead, visibleTail,
} from './debug'

describe('agent debug', () => {
  it('falls back to chat when the manifest does not declare a mode', () => {
    expect(debugMode(null)).toBe('chat')
    expect(debugMode({})).toBe('chat')
    expect(debugMode({ interaction: {} })).toBe('chat')
    expect(debugMode({ interaction: { mode: 'schedule', schedule: '0 2 * * *' } })).toBe('schedule')
    expect(debugMode({ interaction: { mode: 'unknown' as never } })).toBe('chat')
  })

  it('blocks drafts and retired agents but only warns when offline', () => {
    expect(debugBlocked('DRAFT')).toContain('还没注册')
    expect(debugBlocked('RETIRED')).toContain('已下线')
    expect(debugBlocked('ONLINE')).toBeNull()
    expect(debugBlocked('OFFLINE')).toBeNull()
    expect(debugWarning('OFFLINE')).toContain('离线')
    expect(debugWarning('ONLINE')).toBeNull()
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

  it('pages the three columns and keeps only the latest window', () => {
    const items = ['a', 'b', 'c', 'd']
    expect(visibleHead(items, 2)).toEqual(['a', 'b'])
    expect(visibleTail(items, 2)).toEqual(['c', 'd'])
    expect(visibleTail(items, 9)).toEqual(items)
    expect(pageStatus(2, 4, '个', 'down')).toBe('↓ 下滑加载更多 · 已显示 2 / 4 个')
    expect(pageStatus(2, 4, '条', 'up', true)).toBe('加载中…')
    expect(pageStatus(4, 4, '条', 'up')).toBe('已全部加载 · 共 4 条')
    expect(appendEvents([1, 2], [3, 4], 3)).toEqual([2, 3, 4])
  })

  it('keeps a week of this browser sessions and names them from the first line', () => {
    const now = Date.parse('2026-10-18T09:00:00Z')
    const week = 7 * 86_400_000
    expect(pruneSessions([{ at: now - week - 1 }, { at: now - 1000 }], now)).toEqual([{ at: now - 1000 }])
    expect(byRecent([{ at: 1 }, { at: 3 }, { at: 2 }]).map((item) => item.at)).toEqual([3, 2, 1])
    expect(sessionTitle('', '我叫小林，在找产品经理岗位的建议')).toBe('我叫小林，在找产品经理岗位的')
    expect(sessionTitle('已有标题', '新的一句')).toBe('已有标题')
    expect(debugStoreKey('u_88', 'careermate', 'dev')).toBe('keel-debug:u_88:careermate:dev')
    const raw = JSON.stringify([
      { id: 'ss_ok', title: '改简历', at: now - 1000, messages: [{ role: 'user', text: '你好' }], events: [{ kind: 'final', text: '1.2s' }] },
      { id: 'bad id', title: '', at: now, messages: [], events: [] },
      { id: 'ss_old', title: '', at: now - week - 5, messages: [], events: [] },
    ])
    expect(parseSessions(raw, now).map((item) => item.id)).toEqual(['ss_ok'])
    expect(parseSessions('not-json')).toEqual([])
  })

  it('splits SSE frames and reads token, final and guardrail events', () => {
    const first = takeSseFrames('event: token\ndata: {"text":"你"}\n\nevent: fin')
    expect(first.frames).toEqual([{ event: 'token', data: '{"text":"你"}' }])
    expect(first.rest).toBe('event: fin')
    const done = takeSseFrames(`${first.rest}al\ndata: {"answer":"你好","trace_id":"tr_1","run_id":"r"}\n\n`)
    expect(describeLiveFrame(done.frames[0].event, done.frames[0].data)).toMatchObject({ kind: 'final', answer: '你好', traceId: 'tr_1' })
    expect(describeLiveFrame('token', '{"text":"你"}')?.token).toBe('你')
    expect(describeLiveFrame('error', '{"code":"GUARD_INJECTION_BLOCKED","message":"拦截"}')?.blocked).toBe(true)
    expect(describeLiveFrame('guardrail', '{"message":"输入注入 · 命中"}')?.hit).toBe(true)
  })

  it('turns a trace step into a realtime event', () => {
    expect(traceEvent({ id: '1', name: 'memory.recall', type: '工具', depth: 0, durationMs: 12, status: 'ok', output: '2 条' }))
      .toEqual({ kind: 'tool', text: 'memory.recall · 2 条', hit: false })
    expect(traceEvent({ id: '2', name: '输入注入', type: '护栏', depth: 0, durationMs: 4, status: 'failed', output: '' }))
      .toMatchObject({ kind: 'guardrail', hit: true })
  })
})
