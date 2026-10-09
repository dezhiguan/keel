import { describe, expect, it } from 'vitest'
import { eventKind, eventTime, filterAuditPage, payloadText, shortHash } from './auditDrawer'

describe('audit drawer', () => {
  it('formats the event time like the prototype', () => {
    expect(eventTime('2026-09-30T12:31:02+08:00')).toBe('2026-09-30 12:31:02')
    expect(eventTime(null)).toBe('—')
  })

  it('shortens a chain hash to sha256 plus eight hex digits', () => {
    expect(shortHash('170951efab')).toBe('sha256:170951ef…')
    expect(shortHash('sha256:170951efab')).toBe('sha256:170951ef…')
    expect(shortHash('')).toBe('—')
    expect(shortHash(null)).toBe('—')
  })

  it('pretty-prints the whitelisted payload', () => {
    expect(payloadText({ tables: ['returns', 'orders'], rows: 3 })).toBe(
      '{\n  "tables": [\n    "returns",\n    "orders"\n  ],\n  "rows": 3\n}',
    )
    expect(payloadText(null)).toBe('{}')
  })

  it('keeps the page unchanged until an event kind is chosen', () => {
    const items = [
      { action: 'tool.call', payload: {} },
      { action: 'config.change', payload: { kind: 'devflow.takeover' } },
      { action: 'config.change', payload: { kind: 'devflow.stage' } },
    ]
    expect(filterAuditPage(items, undefined, undefined)).toEqual(items)
    expect(filterAuditPage(items, 'config.change', undefined)).toHaveLength(2)
  })

  it('keeps only takeover events when that kind is selected', () => {
    const items = [
      { action: 'config.change', payload: { kind: 'devflow.takeover' } },
      { action: 'config.change', payload: { kind: 'devflow.stage' } },
      { action: 'run.suspend', payload: {} },
    ]
    expect(filterAuditPage(items, 'config.change', 'devflow.takeover').map((item) => eventKind(item.payload))).toEqual(['devflow.takeover'])
  })

  it('drops the kind filter when the action is no longer config.change', () => {
    const items = [
      { action: 'run.suspend', payload: {} },
      { action: 'config.change', payload: { kind: 'devflow.takeover' } },
    ]
    expect(filterAuditPage(items, 'run.suspend', 'devflow.takeover')).toEqual([items[0]])
  })
})