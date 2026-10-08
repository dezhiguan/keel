import { setupServer } from 'msw/node'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { handlers } from './handlers'

const server = setupServer(...handlers)
const BASE = 'http://localhost/api/v1'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

async function call(method: 'GET' | 'POST' | 'PUT', path: string, body?: unknown) {
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

  it('rejects a breaking publish on the same name', async () => {
    const res = await call('POST', '/tools/alarm_query/versions', { version: 'v3', breaking: true })
    expect(res.status).toBe(400)
    expect(res.json.message).toContain('新名字')
  })

  it('publishes a compatible version and names the dependents', async () => {
    const res = await call('POST', '/tools/alarm_query/versions', { version: 'v3', breaking: false })
    expect(res.status).toBe(200)
    expect(res.json.data.triggeredRegressions).toContain('offshore-wind')
  })

  it('registers a new tool and refuses a duplicate name', async () => {
    const body = { name: 'echo.note.v2', scope: 'PRIVATE', access: 'READ', risk: 'LOW', provider: 'mcp://echo/note', schemaJson: {} }
    const created = await call('POST', '/tools', body)
    expect(created.status).toBe(200)
    const again = await call('POST', '/tools', body)
    expect(again.status).toBe(400)
    expect(again.json.message).toContain('已存在')
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

  it('lists the devflow board with the prototype sample', async () => {
    const res = await call('GET', '/devflow/jobs')
    expect(res.status).toBe(200)
    expect(res.json.data.summary).toMatchObject({ active: 8, queued: 2, waitingHuman: 4, humanDev: 1, dailyLimit: 3 })
    expect(res.json.data.summary.spentCny).toBeCloseTo(227.6)
    expect(res.json.data.items.map((job: { jobId: string }) => job.jobId)).toContain('DF-0019')
    expect((await call('GET', '/devflow/jobs/DF-missing')).status).toBe(404)
  })

  it('rejects takeover of a finished job and accepts a review that is waiting', async () => {
    const denied = await call('POST', '/devflow/jobs/DF-0013/takeover')
    expect(denied.status).toBe(400)
    expect(denied.json.code).toBe('SERVER_INVALID_PARAM')
    const taken = await call('POST', '/devflow/jobs/DF-0021/takeover')
    expect(taken.json.data).toMatchObject({ status: 'HUMAN', stage: 'BUILD', humanDevUser: '官德志' })
  })

  it('assists and hands back a human-owned job, and refuses an empty instruction', async () => {
    const empty = await call('POST', '/devflow/jobs/DF-0015/assist', { instruction: '  ' })
    expect(empty.status).toBe(400)
    expect(empty.json.message).toContain('写明')
    const helped = await call('POST', '/devflow/jobs/DF-0015/assist', { instruction: '补 tools/ 的单测' })
    expect(helped.json.data.events.at(-1).summary).toContain('推送')
    const back = await call('POST', '/devflow/jobs/DF-0015/handback')
    expect(back.json.data).toMatchObject({ status: 'RUN', stage: 'REVIEW' })
  })

  it('refuses to cancel a job in production watch and cancels a queued one', async () => {
    const watched = await call('POST', '/devflow/jobs/DF-0014/cancel')
    expect(watched.status).toBe(400)
    const queued = await call('POST', '/devflow/jobs/DF-0022/cancel')
    expect(queued.json.data.status).toBe('CANCEL')
  })

  it('previews a batch, skips flagged rows, and saves rules', async () => {
    const preview = await call('POST', '/devflow/batches/preview', {})
    expect(preview.json.data.rows).toHaveLength(5)
    const created = await call('POST', '/devflow/batches', { title: '客服中心 Q4 第一批', rows: preview.json.data.rows })
    expect(created.status).toBe(200)
    expect(created.json.data.jobs).toHaveLength(3)
    expect(created.json.data.jobs[0].status).toBe('RUN')
    expect(created.json.data.jobs[1].status).toBe('QUEUED')
    const empty = await call('PUT', '/devflow/settings', { budgetCny: 80, maxFixRounds: 3, holdoutPercent: 30, minSeed: 30, keyCapCny: 50, dailyLimit: 3, concurrency: 3, templates: [] })
    expect(empty.status).toBe(400)
    const saved = await call('PUT', '/devflow/settings', { budgetCny: 90, maxFixRounds: 3, holdoutPercent: 30, minSeed: 30, keyCapCny: 50, dailyLimit: 3, concurrency: 3, templates: ['tool-agent', 'chat-rag'] })
    expect(saved.json.data.budgetCny).toBe(90)
    expect((await call('GET', '/devflow/settings')).json.data.templates).toContain('chat-rag')
  })

  it('still mocks shared services while traces, audit, costs and eval are real', async () => {
    const services = await call('GET', '/insight/services')
    expect(services.status).toBe(200)
    expect(services.json.data.services.length).toBeGreaterThan(0)
    expect(services.json.data.components.map((row: { name: string }) => row.name)).toContain('keel-devflow-sandbox')
    expect(services.json.data.components.find((row: { name: string }) => row.name === 'keel-devflow-sandbox').usage).toBeNull()
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
