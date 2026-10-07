import { http, HttpResponse } from 'msw'
import { sharedServices } from './data/services'
import { dependentsOf, toolDetail, tools } from './data/tools'
import { approvals, suspendedRuns } from './data/approvals'

// Leading wildcard so the same handlers match in the browser and under msw/node (which has no page origin).
const BASE = '*/api/v1'

let consoleSession: 'user' | null = null
const consoleUser = {
  userId: 'local-console-user',
  displayName: '官德志',
  org: '平台组',
  platformRole: 'ADMIN',
  roles: ['ADMIN'],
  visibleAgents: [],
  pendingApprovals: 0,
  mode: 'USER',
  readOnly: false,
}
const previewUser = {
  userId: '',
  displayName: '预览访客',
  org: '',
  platformRole: 'VIEWER',
  roles: [],
  visibleAgents: [],
  pendingApprovals: 0,
  mode: 'PREVIEW',
  readOnly: true,
}

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
  http.get(`${BASE}/auth/options`, () => ok({ methods: ['password', 'sms'], previewEnabled: true })),
  http.get(`${BASE}/auth/captcha`, () => ok({ captchaImage: '', challengeId: 'mock' })),
  http.post(`${BASE}/auth/sms/send`, () => ok({ sent: true, expiresIn: 300 })),
  http.post(`${BASE}/auth/login/password`, async ({ request }) => {
    const body = (await request.json()) as { account?: string; password?: string }
    if (body.account === 'console-user' && body.password) {
      consoleSession = 'user'
      return ok(consoleUser)
    }
    return fail(401, 'AUTH_BAD_CREDENTIALS', '账号或密码不正确。没有账号或忘记密码，请联系平台管理员')
  }),
  http.post(`${BASE}/auth/login/sms`, async ({ request }) => {
    const body = (await request.json()) as { phone?: string; code?: string }
    if (body.code === '123456') {
      consoleSession = 'user'
      return ok(consoleUser)
    }
    return fail(401, 'AUTH_BAD_CREDENTIALS', '账号或密码不正确。没有账号或忘记密码，请联系平台管理员')
  }),
  http.post(`${BASE}/auth/logout`, () => {
    consoleSession = null
    return new HttpResponse(null, { status: 204 })
  }),
  http.post(`${BASE}/auth/refresh`, () => new HttpResponse(null, { status: 204 })),
  http.get(`${BASE}/me`, () => ok(consoleSession === 'user' ? consoleUser : previewUser)),

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

  http.post(`${BASE}/tools`, async ({ request }) => {
    const body = (await request.json()) as {
      name?: string
      description?: string
      scope?: (typeof tools)[number]['scope']
      access?: (typeof tools)[number]['access']
      risk?: (typeof tools)[number]['risk']
      provider?: string
      ownerAgent?: string
    }
    if (!body.name || !body.provider) return fail(400, 'SERVER_INVALID_PARAM', '工具名和 provider 不能为空')
    if (tools.some((tool) => tool.name === body.name)) return fail(400, 'SERVER_INVALID_PARAM', '工具名已存在')
    tools.push({
      name: body.name,
      description: body.description || body.name,
      scope: body.scope ?? 'PRIVATE',
      ownerAgent: body.ownerAgent,
      access: body.access ?? 'READ',
      risk: body.risk ?? 'LOW',
      version: 'v1',
      status: 'REGISTERED',
      calls24h: 0,
      dependentCount: 0,
    })
    return ok({ name: body.name })
  }),

  http.post(`${BASE}/tools/:name/versions`, async ({ params, request }) => {
    const tool = tools.find((item) => item.name === params.name)
    if (!tool) return fail(404, 'SERVER_NOT_FOUND', '资源不存在')
    if (tool.status === 'RETIRED') return fail(409, 'TOOL_RETIRED', '工具已下线')
    const body = (await request.json()) as { version?: string; description?: string; risk?: (typeof tools)[number]['risk']; breaking?: boolean }
    if (body.breaking) return fail(400, 'SERVER_INVALID_PARAM', '破坏兼容必须用新名字注册，不能在原名上发版')
    if (!body.version) return fail(400, 'SERVER_INVALID_PARAM', '版本不能为空')
    tool.version = body.version
    if (body.description) tool.description = body.description
    if (body.risk) tool.risk = body.risk
    if (tool.status === 'REGISTERED') tool.status = 'ONLINE'
    return ok({ triggeredRegressions: dependentsOf(tool.name!).flatMap((item) => (item.agent ? [item.agent] : [])) })
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
