import { http, HttpResponse } from 'msw'
import { sharedServices } from './data/services'
import { dependentsOf, toolDetail, tools } from './data/tools'
import { approvals, suspendedRuns } from './data/approvals'

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

export const handlers = [
  http.get(`${BASE}/insight/services`, () => ok(sharedServices)),

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

  http.post(`${BASE}/approvals`, async ({ request }) => {
    const body = (await request.json()) as {
      subjectType: (typeof approvals)[number]['subjectType']
      subjectRef: string
      summary: string
      actorUser: string
      agent?: string
      risk?: (typeof approvals)[number]['risk']
    }
    const created = {
      id: `ap_${approvals.length + 1000}`,
      subjectType: body.subjectType,
      subjectRef: body.subjectRef,
      summary: body.summary,
      actorUser: body.actorUser,
      agent: body.agent,
      risk: body.risk ?? 'HIGH',
      status: 'PENDING' as const,
      createdAt: new Date().toISOString(),
    }
    approvals.unshift(created)
    return ok(created)
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

]
