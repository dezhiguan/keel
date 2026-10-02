import { delay, http, HttpResponse } from 'msw'
import { sharedServices } from './data/services'
import { traceDetail, traceSummaries } from './data/traces'
import { latestEval } from './data/eval'
import { dependentsOf, toolDetail, tools } from './data/tools'
import { approvals, suspendedRuns } from './data/approvals'
import { auditEvents } from './data/audit'
import { modelGateway } from './data/models'

// Leading wildcard so the same handlers match in the browser and under msw/node (which has no page origin).
const BASE = '*/api/v1'

const traceId = () => Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('')
const ok = (data: unknown, status = 200) => HttpResponse.json({ code: 'OK', message: null, traceId: traceId(), data }, { status })
const fail = (status: number, code: string, message: string) =>
  HttpResponse.json({ code, message, traceId: traceId(), retryable: false }, { status })

function paged<T>(url: URL, list: T[]) {
  const page = Math.max(1, Number(url.searchParams.get('page') ?? 1))
  const size = Number(url.searchParams.get('size') ?? 10)
  return { page, size, total: list.length, items: list.slice((page - 1) * size, page * size) }
}

const evalRuns = new Map<string, { agent: string; startedAt: number }>()

export const handlers = [
  http.get(`${BASE}/insight/services`, () => ok(sharedServices)),

  http.get(`${BASE}/insight/traces`, ({ request }) => {
    const url = new URL(request.url)
    const agent = url.searchParams.get('agent')
    const status = url.searchParams.get('status')
    const env = url.searchParams.get('env')
    const list = traceSummaries.filter(
      (t) =>
        (!agent || t.agents?.includes(agent)) &&
        (!status || t.status === status) &&
        (!env || env === 'all' || t.env === env),
    )
    return ok(paged(url, list))
  }),

  http.get(`${BASE}/insight/traces/:traceId`, ({ params }) => {
    const detail = traceDetail(String(params.traceId))
    return detail ? ok(detail) : fail(404, 'SERVER_NOT_FOUND', '资源不存在')
  }),

  http.get(`${BASE}/eval/:agent/latest`, ({ params }) => {
    const result = latestEval(String(params.agent))
    return result ? ok(result) : fail(404, 'SERVER_NOT_FOUND', '该智能体还没有评测记录')
  }),

  http.post(`${BASE}/eval/:agent/runs`, ({ params }) => {
    const runId = `ev_${Date.now().toString(36)}`
    evalRuns.set(runId, { agent: String(params.agent), startedAt: Date.now() })
    return ok({ runId }, 202)
  }),

  http.get(`${BASE}/eval/runs/:runId`, ({ params }) => {
    const run = evalRuns.get(String(params.runId))
    if (!run) return fail(404, 'SERVER_NOT_FOUND', '资源不存在')
    const progress = Math.min(1, (Date.now() - run.startedAt) / 3000)
    const done = progress >= 1
    return ok({ runId: params.runId, state: done ? 'DONE' : 'RUNNING', progress, result: done ? latestEval(run.agent) : null })
  }),

  http.get(`${BASE}/tools`, ({ request }) => {
    const url = new URL(request.url)
    const scope = url.searchParams.get('scope')
    const status = url.searchParams.get('status')
    const risk = url.searchParams.get('risk')
    const list = tools.filter(
      (t) => (!scope || scope === 'all' || t.scope === scope) && (!status || t.status === status) && (!risk || t.risk === risk),
    )
    return ok(paged(url, list))
  }),

  http.get(`${BASE}/tools/:name`, ({ params }) => {
    const tool = tools.find((t) => t.name === params.name)
    return tool ? ok(toolDetail(tool)) : fail(404, 'SERVER_NOT_FOUND', '资源不存在')
  }),

  http.post(`${BASE}/tools/:name/deprecate`, async ({ params, request }) => {
    const tool = tools.find((t) => t.name === params.name)
    if (!tool) return fail(404, 'SERVER_NOT_FOUND', '资源不存在')
    const body = (await request.json()) as { replacedBy: string; deadline: string }
    Object.assign(tool, { status: 'DEPRECATED', replacedBy: body.replacedBy, deprecateDeadline: body.deadline })
    return ok(null)
  }),

  http.post(`${BASE}/tools/:name/retire`, ({ params }) => {
    const tool = tools.find((t) => t.name === params.name)
    if (!tool) return fail(404, 'SERVER_NOT_FOUND', '资源不存在')
    const prod = dependentsOf(tool.name!).filter((d) => d.env === 'prod')
    if (prod.length) {
      return fail(409, 'TOOL_HAS_PROD_DEPENDENTS', `仍有 prod 依赖方：${prod.map((d) => d.agent).join('、')}，只能先废弃`)
    }
    tool.status = 'RETIRED'
    return ok(null)
  }),

  http.get(`${BASE}/approvals`, ({ request }) => {
    const url = new URL(request.url)
    const status = url.searchParams.get('status')
    return ok(paged(url, approvals.filter((a) => !status || a.status === status)))
  }),

  http.post(`${BASE}/approvals/:id/decision`, async ({ params, request }) => {
    const approval = approvals.find((a) => a.id === params.id)
    if (!approval) return fail(404, 'SERVER_NOT_FOUND', '资源不存在')
    if (approval.status !== 'PENDING') return fail(409, 'APPROVAL_EXPIRED', '单子已过期或已被处理')
    const body = (await request.json()) as { decision: 'APPROVE' | 'REJECT' }
    Object.assign(approval, {
      status: body.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED',
      decidedBy: 'dev',
      decidedAt: new Date().toISOString(),
    })
    return ok(approval)
  }),

  http.get(`${BASE}/runs`, ({ request }) => ok(paged(new URL(request.url), suspendedRuns))),

  http.post(`${BASE}/runs/:runId/input`, ({ params }) => {
    const index = suspendedRuns.findIndex((r) => r.runId === params.runId)
    if (index < 0) return fail(409, 'RUN_NOT_RESUMABLE', '该次执行不处于可恢复状态')
    suspendedRuns.splice(index, 1)
    return ok(null)
  }),

  http.get(`${BASE}/audit/events`, ({ request }) => {
    const url = new URL(request.url)
    const agent = url.searchParams.get('agent')
    const risk = url.searchParams.get('risk')
    return ok(paged(url, auditEvents.filter((e) => (!agent || e.agent === agent) && (!risk || e.risk === risk))))
  }),

  http.post(`${BASE}/audit/verify`, async ({ request }) => {
    await delay(1200)
    const agent = new URL(request.url).searchParams.get('agent')
    const checked = auditEvents.filter((e) => !agent || e.agent === agent).length
    return ok({ checked, intact: true, brokenAt: null, elapsedMs: 1200 })
  }),

  http.post(`${BASE}/audit/exports`, () => ok({ exportId: `ex_${Date.now().toString(36)}`, approvalId: 'ap_0927' }, 202)),

  http.get(`${BASE}/insight/costs`, () => ok(modelGateway)),
]
