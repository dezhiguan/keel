import type { ToolDetail, ToolSummary } from '@/api/tools'
import type { components } from '@/api/schema'

type Dependent = components['schemas']['ToolDependent']

export const tools: ToolSummary[] = [
  { name: 'rag.search', description: 'rag-forge 知识检索', scope: 'SHARED', ownerAgent: 'rag-forge', access: 'READ', risk: 'LOW', version: 'v3', status: 'ONLINE', approvalPolicy: '无需审批', calls24h: 9870 },
  { name: 'askdb.query', description: '通过 askdb 自然语言查数', scope: 'SHARED', ownerAgent: 'askdb', access: 'READ', risk: 'MID', version: 'v2', status: 'ONLINE', approvalPolicy: '敏感表需审批', calls24h: 1480 },
  { name: 'sql.execute', description: '只读数仓查询', scope: 'PRIVATE', ownerAgent: 'askdb', access: 'READ', risk: 'MID', version: 'v4', status: 'ONLINE', approvalPolicy: '敏感表需审批', calls24h: 3511 },
  { name: 'work_order_create', description: '创建维检工单（工单系统 MCP）', scope: 'SHARED', ownerOrg: '工单系统组', access: 'WRITE', risk: 'HIGH', version: 'v1', status: 'ONLINE', approvalPolicy: '班组长审批', calls24h: 23 },
  { name: 'work_order_query', description: '查询工单（旧接口）', scope: 'SHARED', ownerOrg: '工单系统组', access: 'READ', risk: 'LOW', version: 'v1', status: 'DEPRECATED', approvalPolicy: '无需审批', calls24h: 588, replacedBy: 'work_order_search', deprecateDeadline: '2026-10-31' },
  { name: 'alarm_query', description: 'SCADA 告警查询', scope: 'PRIVATE', ownerAgent: 'offshore-wind', access: 'READ', risk: 'LOW', version: 'v2', status: 'ONLINE', approvalPolicy: '无需审批', calls24h: 588 },
  { name: 'resume.update', description: '修改简历', scope: 'PRIVATE', ownerAgent: 'careermate', access: 'WRITE', risk: 'MID', version: 'v1', status: 'ONLINE', approvalPolicy: '用户本人确认', calls24h: 412 },
  { name: 'resume.delete', description: '删除简历', scope: 'PRIVATE', ownerAgent: 'careermate', access: 'WRITE', risk: 'HIGH', version: 'v1', status: 'ONLINE', approvalPolicy: '确认 + 24h 冷却', calls24h: 6 },
  { name: 'git.pr.diff', description: '读取 PR 变更', scope: 'SHARED', ownerOrg: '研发效能组', access: 'READ', risk: 'LOW', version: 'v1', status: 'ONLINE', approvalPolicy: '无需审批', calls24h: 469 },
  { name: 'git.pr.comment', description: '在 PR 上发表评审意见', scope: 'SHARED', ownerOrg: '研发效能组', access: 'WRITE', risk: 'MID', version: 'v1', status: 'ONLINE', approvalPolicy: '无需审批', calls24h: 398 },
  { name: 'git.pr.create', description: '创建修复 PR', scope: 'SHARED', ownerOrg: '研发效能组', access: 'WRITE', risk: 'HIGH', version: 'v1', status: 'ONLINE', approvalPolicy: '仓库维护者审批', calls24h: 9 },
  { name: 'ci.log.fetch', description: '拉取 CI 构建日志', scope: 'SHARED', ownerOrg: '研发效能组', access: 'READ', risk: 'LOW', version: 'v1', status: 'ONLINE', approvalPolicy: '无需审批', calls24h: 64 },
  { name: 'maven.test.run', description: '在沙箱容器执行 Maven 测试', scope: 'PRIVATE', ownerAgent: 'test-gen', access: 'EXEC', risk: 'MID', version: 'v2', status: 'ONLINE', approvalPolicy: '仅沙箱', calls24h: 57 },
  { name: 'jira.issue.create', description: '创建需求 / 任务单', scope: 'SHARED', ownerOrg: '研发效能组', access: 'WRITE', risk: 'MID', version: 'v1', status: 'ONLINE', approvalPolicy: '无需审批', calls24h: 31 },
  { name: 'sql.legacy_export', description: '旧版数据导出（90 天无调用）', scope: 'SHARED', ownerOrg: '数据平台组', access: 'READ', risk: 'HIGH', version: 'v1', status: 'ONLINE', approvalPolicy: '数据负责人审批', calls24h: 0 },
]

const AGENT_TOOLS: Record<string, { env: Dependent['env']; status: Dependent['agentStatus']; tools: string[] }> = {
  careermate: { env: 'prod', status: 'ONLINE', tools: ['resume.update', 'resume.delete', 'rag.search'] },
  askdb: { env: 'prod', status: 'DEGRADED', tools: ['sql.execute', 'rag.search'] },
  'offshore-wind': { env: 'staging', status: 'REGISTERED', tools: ['alarm_query', 'work_order_query', 'work_order_create', 'rag.search'] },
  'cs-bot': { env: 'prod', status: 'ONLINE', tools: ['rag.search'] },
  'ops-copilot': { env: 'staging', status: 'ONLINE', tools: ['askdb.query', 'work_order_create'] },
  'prd-agent': { env: 'staging', status: 'ONLINE', tools: ['jira.issue.create'] },
  'code-review': { env: 'prod', status: 'ONLINE', tools: ['git.pr.diff', 'git.pr.comment', 'rag.search'] },
  'test-gen': { env: 'staging', status: 'OFFLINE', tools: ['maven.test.run', 'git.pr.diff'] },
  'dev-copilot': { env: 'staging', status: 'REGISTERED', tools: ['git.pr.create', 'askdb.query'] },
}

export function dependentsOf(tool: string): Dependent[] {
  return Object.entries(AGENT_TOOLS)
    .filter(([, a]) => a.tools.includes(tool))
    .map(([agent, a]) => ({ agent, env: a.env, agentStatus: a.status, declaredInManifest: true, calls30d: 0 }))
}

tools.forEach((t) => (t.dependentCount = dependentsOf(t.name!).length))

export function toolDetail(tool: ToolSummary): ToolDetail {
  return {
    ...tool,
    provider: tool.scope === 'SHARED' ? `mcp://${tool.ownerAgent ?? 'tools'}.internal/${tool.name}` : null,
    schemaJson: { type: 'object' },
    dependents: dependentsOf(tool.name!),
    versions: [
      { version: tool.version, change: '当前', breaking: false, at: '2026-09-12T10:00:00+08:00' },
      { version: '初版', change: '注册', breaking: false, at: '2026-07-30T10:00:00+08:00' },
    ],
  }
}
