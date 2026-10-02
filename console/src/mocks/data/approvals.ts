import type { Approval, SuspendedRun } from '@/api/approvals'

const minutesAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString()

export const approvals: Approval[] = [
  { id: 'ap_0912', subjectType: 'tool.call', subjectRef: 'work_order_create', tool: 'work_order_create', runId: 'r_41c8', risk: 'HIGH', agent: 'ops-copilot', policyName: '班组长审批', actorUser: '张工', createdAt: minutesAgo(18), traceId: 'tr_8a31f0c2', status: 'PENDING', payloadDigest: 'sha256:9c1e…', summary: '运维总控代张工创建工单：WT-07 变桨系统 F203，优先级 P1，24h 内登机检查。' },
  { id: 'ap_0915', subjectType: 'tool.call', subjectRef: 'git.pr.create', tool: 'git.pr.create', runId: 'r_52d1', risk: 'HIGH', agent: 'dev-copilot', policyName: '仓库维护者审批', actorUser: 'amy', createdAt: minutesAgo(11), traceId: 'tr_d71e0b33', status: 'PENDING', payloadDigest: 'sha256:4b2a…', summary: '研发总控为需求 REQ-218 创建修复 PR：order-service 分页接口越界修复，改动 3 个文件。' },
  { id: 'ap_0917', subjectType: 'tool.call', subjectRef: 'sql.execute · hr.salary', tool: 'sql.execute', runId: 'r_6093', risk: 'MID', agent: 'askdb', policyName: '数据负责人审批', actorUser: 'analyst@org_7', createdAt: minutesAgo(9), traceId: 'tr_4f02aa18', status: 'PENDING', payloadDigest: 'sha256:77d0…', summary: 'askdb 代 analyst@org_7 查询部门平均薪资（聚合查询，不含个人明细）。' },
  { id: 'ap_0919', subjectType: 'tool.config', subjectRef: 'sql.legacy_export', tool: 'sql.legacy_export', runId: null, risk: 'HIGH', agent: '数据平台组', policyName: '数据负责人审批', actorUser: '刘工', createdAt: minutesAgo(60), status: 'PENDING', payloadDigest: 'sha256:0a9f…', summary: '申请把 sql.legacy_export 风险等级从高调为中（90 天无调用）。调低风险等级本身要走审批，批准后立即生效并通知依赖方。' },
  { id: 'ap_0923', subjectType: 'agent.config', subjectRef: 'offshore-wind', runId: null, risk: 'HIGH', agent: 'offshore-wind', policyName: '负责人审批', actorUser: '张工', createdAt: minutesAgo(26), status: 'PENDING', payloadDigest: 'sha256:c3e4…', summary: '张工申请把接地校验从 enforce 改为 shadow：误拦率偏高，先观察两周。护栏降级，批准后写 config.change 审计。' },
  { id: 'ap_0925', subjectType: 'agent.retire', subjectRef: 'night-patrol', runId: null, risk: 'HIGH', agent: 'night-patrol', policyName: '平台管理员审批', actorUser: 'amy', createdAt: minutesAgo(120), status: 'PENDING', payloadDigest: 'sha256:5f61…', summary: '申请下线 night-patrol（7 天无流量，已判定 ZOMBIE）。批准后吊销 LiteLLM 虚拟 Key、删除 Secret keel-night-patrol，状态置 RETIRED。' },
  { id: 'ap_0927', subjectType: 'data.export', subjectRef: 'audit_export · 7d', runId: null, risk: 'HIGH', agent: 'keel-audit', policyName: '安全负责人审批', actorUser: 'amy', createdAt: minutesAgo(34), status: 'PENDING', payloadDigest: 'sha256:e812…', summary: 'amy 申请导出 cs-bot 近 7 天审计记录（约 1.2 万条）用于季度合规检查。批准后生成脱敏文件，导出动作本身也写审计。' },
]

export const suspendedRuns: SuspendedRun[] = [
  { runId: 'r_7b4a', agent: 'offshore-wind', env: 'staging', traceId: 'tr_5c1e9a4f', reason: 'input_required', actorUser: '张工', createdAt: minutesAgo(3), deadline: null, prompt: '检修建议涉及两台风机，WT-07 和 WT-11 的停机窗口冲突。请指定先检修哪一台，智能体据此继续排期。' },
]
