import type { AuditEvent } from '@/api/audit'

type Seed = [agent: string, user: string, action: AuditEvent['action'], resource: string, risk: AuditEvent['risk'], decision: AuditEvent['decision'], payload: Record<string, unknown>, traceId: string | null]

const today = (hms: string) => `2026-10-02T${hms}+08:00`

const head: [string, ...Seed][] = [
  ['12:44:10', 'offshore-wind', 'CI · keel-gate', 'release.gate', 'v0.7.2 → prod', 'MID', 'denied', { score: 0.84, rule: 'minScore 0.85', regressed: ['安全规程 -6pt'] }, 'tr_gate_7c2'],
  ['12:39:30', 'ops-copilot', '张工 u_88', 'tool.call', 'work_order.create', 'HIGH', 'pending', { turbine_id: 'WT-07', priority: 'P1' }, 'tr_8a31f0c2'],
  ['12:31:02', 'askdb', 'analyst@org_7', 'sql.execute', 'dw_prod.returns', 'MID', 'allowed', { tables: ['returns', 'orders'], rows: 3 }, 'tr_4f02aa18'],
  ['12:22:19', 'cs-bot', '访客 v_8812', 'data.export', '对话记录导出', 'HIGH', 'denied', { range: '7d', reason: '未登录用户无导出权限' }, 'tr_c20f5a11'],
  ['11:58:40', 'code-review', 'ci_bot', 'tool.call', 'git.pr.comment', 'MID', 'allowed', { pr: 'order-service#142', findings: 4 }, 'tr_19ab7e02'],
  ['11:20:05', 'careermate', 'u_2031', 'tool.call', 'resume.update', 'MID', 'allowed', { resume_id: 88213, fields: ['project_exp'] }, 'tr_77aa01d4'],
  ['10:53:00', 'meta-agent', 'amy', 'run.suspend', 'DF-0021 等人工 review', 'LOW', 'allowed', { jobId: 'DF-0021' }, null],
  ['10:20:33', 'offshore-wind', 'amy', 'config.change', 'prompt v12 → v13', 'MID', 'allowed', { prompt: 'answer', from: 12, to: 13 }, null],
  ['09:29:00', 'dev-lead', '王工', 'config.change', 'DF-0015 王工接管开发', 'MID', 'allowed', { kind: 'devflow.takeover' }, null],
  ['09:12:00', 'dev-lead', 'dev-lead', 'config.change', 'DF-0020 SPEC → H1', 'LOW', 'allowed', { kind: 'devflow.stage' }, null],
  ['09:05:12', 'prd-agent', 'amy', 'agent.register', 'prd-agent v0.3.0 · staging', 'LOW', 'allowed', { selfcheck: '7/7' }, null],
]

const tail: Seed[] = [
  ['careermate', 'u_2031', 'tool.call', 'resume.update', 'MID', 'allowed', { fields: ['skills'] }, null],
  ['askdb', 'analyst@org_3', 'sql.execute', 'dw_prod.orders', 'MID', 'allowed', { rows: 24 }, null],
  ['code-review', 'ci_bot', 'tool.call', 'git.pr.comment', 'MID', 'allowed', { findings: 2 }, null],
  ['cs-bot', '访客 v_90', 'invoke', '售后咨询', 'LOW', 'allowed', { turns: 3 }, null],
  ['offshore-wind', '张工 u_88', 'tool.call', 'alarm_query', 'LOW', 'allowed', { turbine_id: 'WT-11' }, null],
  ['askdb', 'analyst@org_7', 'sql.execute', 'hr.salary', 'HIGH', 'denied', { reason: '敏感表未审批' }, null],
  ['prd-agent', 'amy', 'invoke', '需求 REQ-231', 'LOW', 'allowed', { sections: 6 }, null],
  ['ops-copilot', '李工 u_12', 'approval', 'work_order.create', 'HIGH', 'approved', { ticket: 'WO-031' }, null],
]

const ENV: Record<string, AuditEvent['env']> = { careermate: 'prod', askdb: 'prod', 'cs-bot': 'prod', 'code-review': 'prod' }

function event(i: number, ts: string, [agent, user, action, resource, risk, decision, payload, traceId]: Seed): AuditEvent {
  return {
    eventId: `ae_${(0x4e20 + i * 131).toString(16)}`,
    ts,
    agent,
    env: ENV[agent] ?? 'staging',
    traceId,
    actor: { userId: user, orgId: null, role: null },
    action,
    resource,
    risk,
    decision,
    approver: decision === 'approved' ? '李工' : null,
    payload,
    inputDigest: `sha256:${(0x9f3a00 + i * 977).toString(16)}…`,
    hashVerified: true,
  }
}

export const auditEvents: AuditEvent[] = [
  ...head.map(([hms, ...seed], i) => event(i, today(hms), seed)),
  ...Array.from({ length: 32 }, (_, i) => {
    const m = 8 * 60 + 50 - i * 9
    const hms = `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}:${String((i * 17) % 60).padStart(2, '0')}`
    const seed = tail[i % tail.length]
    return event(head.length + i, today(hms), [...seed.slice(0, 7), `tr_${(0x5a1c00 + i * 977).toString(16)}`] as Seed)
  }),
]
