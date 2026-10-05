import { describe, expect, it } from 'vitest'
import { eventTime, payloadText, shortHash } from './auditDrawer'

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
})