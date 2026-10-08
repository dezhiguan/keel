import { describe, expect, it } from 'vitest'
import { platformRows, SANDBOX_USAGE_PLACEHOLDER } from './platform'

describe('platformRows', () => {
  it('keeps probed status and fills sandbox usage only when nothing was measured', () => {
    const { rows, sandboxPlaceholder } = platformRows(undefined, [
      { name: '薄网关', status: 'ONLINE' },
      { name: 'keel-gateway', status: 'DEGRADED' },
    ])
    expect(rows.map((row) => row.name)).toEqual([
      'keel-server', 'keel-llm', 'keel-audit', 'keel-gateway', 'console', 'keel-devflow-sandbox',
    ])
    expect(rows.find((row) => row.name === 'keel-llm')?.status).toBe('ONLINE')
    expect(rows.find((row) => row.name === 'keel-gateway')?.status).toBe('DEGRADED')
    expect(rows.find((row) => row.name === 'console')?.usage).toBeNull()
    expect(rows.find((row) => row.name === 'keel-devflow-sandbox')).toMatchObject({
      namespace: 'keel-devflow-sandbox',
      status: 'ONLINE',
      usage: SANDBOX_USAGE_PLACEHOLDER,
    })
    expect(sandboxPlaceholder).toBe(true)
  })

  it('leaves a measured sandbox row alone', () => {
    const { rows, sandboxPlaceholder } = platformRows([
      { name: 'keel-devflow-sandbox', namespace: 'keel-devflow-sandbox', status: 'DEGRADED', usage: '2 / 4 核', note: '一次性 Job' },
    ], [])
    expect(sandboxPlaceholder).toBe(false)
    expect(rows[0].usage).toBe('2 / 4 核')
    expect(rows[0].status).toBe('DEGRADED')
  })
})
