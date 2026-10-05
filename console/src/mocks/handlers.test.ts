import { setupServer } from 'msw/node'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { handlers } from './handlers'

const server = setupServer(...handlers)
const BASE = 'http://localhost/api/v1'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

async function call(method: 'GET' | 'POST', path: string, body?: unknown) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return { status: res.status, json: await res.json() }
}

describe('mock handlers', () => {
  it('refuses to retire a tool that still has prod dependents', async () => {
    const res = await call('POST', '/tools/rag.search/retire')
    expect(res.status).toBe(409)
    expect(res.json.code).toBe('TOOL_HAS_PROD_DEPENDENTS')
  })

  it('retires a tool without prod dependents', async () => {
    const res = await call('POST', '/tools/sql.legacy_export/retire')
    expect(res.status).toBe(200)
    const tool = await call('GET', '/tools/sql.legacy_export')
    expect(tool.json.data.status).toBe('RETIRED')
  })

  it('decides an approval once, then rejects a second decision', async () => {
    const first = await call('POST', '/approvals/ap_0915/decision', { decision: 'APPROVE' })
    expect(first.json.data.status).toBe('APPROVED')
    const again = await call('POST', '/approvals/ap_0915/decision', { decision: 'REJECT' })
    expect(again.status).toBe(409)
    const pending = await call('GET', '/approvals?status=PENDING&size=100')
    expect(pending.json.data.items.map((a: { id: string }) => a.id)).not.toContain('ap_0915')
  })

  it('resumes a suspended run only once', async () => {
    expect((await call('POST', '/runs/r_7b4a/input', { text: '先修 WT-07' })).status).toBe(200)
    expect((await call('POST', '/runs/r_7b4a/input', { text: 'again' })).json.code).toBe('RUN_NOT_RESUMABLE')
  })

  it('still mocks shared services while traces, audit, costs and eval are real', async () => {
    const services = await call('GET', '/insight/services')
    expect(services.status).toBe(200)
    expect(services.json.data.services.length).toBeGreaterThan(0)
    await expect(call('GET', '/insight/traces')).rejects.toThrow()
    await expect(call('GET', '/insight/traces/tr_missing')).rejects.toThrow()
    await expect(call('GET', '/audit/events')).rejects.toThrow()
    await expect(call('GET', '/insight/costs')).rejects.toThrow()
    await expect(call('POST', '/audit/verify')).rejects.toThrow()
    await expect(call('POST', '/audit/exports')).rejects.toThrow()
    await expect(call('GET', '/eval/offshore-wind/latest')).rejects.toThrow()
    await expect(call('POST', '/eval/offshore-wind/runs')).rejects.toThrow()
  })
})
