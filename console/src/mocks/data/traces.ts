import type { TraceDetail, TraceNode, TraceSummary } from '@/api/traces'

type Agent = NonNullable<TraceDetail['agents']>[number]
type Edge = NonNullable<TraceDetail['edges']>[number]

const LANGFUSE = 'https://langfuse.internal/project/keel-staging/traces'
const at = (minutesAgo: number) => new Date(Date.now() - minutesAgo * 60_000).toISOString()

const PLATFORM: Agent = { key: 'platform', name: '平台', subtitle: 'keel-gateway · keel-audit', color: '#8a97ab', kind: 'platform' }

const n = (node: TraceNode): TraceNode => ({ status: 'ok', depth: 0, criticalPath: false, aggregated: false, auditIds: [], ...node })

const MULTI_ID = 'tr_8a31f0c2'

const multiAgent: TraceDetail = {
  summary: {
    traceId: MULTI_ID,
    question: 'WT-07 最近一周告警频繁，查一下同批次风机的故障率，给出检修建议，需要的话直接建工单',
    startedAt: at(18),
    sessionId: 's_7f21',
    turn: 3,
    userId: 'u_88',
    userRole: '运维工程师',
    env: 'staging',
    rootAgent: 'ops-copilot',
    agents: ['ops-copilot', 'askdb', 'offshore-wind'],
    multiAgent: true,
    durationMs: 11200,
    humanWaitMs: 252000,
    tokens: 10578,
    costCny: 0.0214,
    status: 'fallback',
    blocked: false,
    cached: false,
  },
  langfuseUrl: `${LANGFUSE}/${MULTI_ID}`,
  agents: [
    PLATFORM,
    { key: 'sup', name: '运维总控', subtitle: 'ops-copilot · supervisor', color: '#ff7a45', kind: 'supervisor' },
    { key: 'askdb', name: 'askdb', subtitle: '子智能体 · 跨服务', color: '#5b9cf6', kind: 'sub' },
    { key: 'wind', name: 'offshore-wind', subtitle: '子智能体 · 跨服务', color: '#2ec4b6', kind: 'sub' },
    { key: 'human', name: '人工', subtitle: '审批中心', color: '#f1b44c', kind: 'human' },
  ],
  latencyBreakdown: [
    { label: '总控规划', ms: 1400, agentKey: 'sup' },
    { label: 'offshore-wind', ms: 4900, agentKey: 'wind' },
    { label: '总控汇总', ms: 3400, agentKey: 'sup' },
    { label: '建工单', ms: 600, agentKey: 'human' },
    { label: '平台与输出', ms: 900, agentKey: 'platform' },
  ],
  costBreakdown: [
    { agentKey: 'sup', costCny: 0.0069 },
    { agentKey: 'askdb', costCny: 0.0036 },
    { agentKey: 'wind', costCny: 0.0105 },
    { agentKey: 'platform', costCny: 0.0004 },
  ],
  nodes: [
    n({ id: 'gw', agentKey: 'platform', type: 'event', name: 'gateway.auth', service: 'keel-gateway', startMs: 0, durationMs: 15, inputSummary: 'Bearer JWT（auth-gateway 签发，aud=keel-api）', outputSummary: '角色 ops_engineer 可调用 ops-copilot，配额剩余 91%，已换票为 aud=ops-copilot，路由到 v0.2.0' }),
    n({ id: 'gin', agentKey: 'platform', type: 'guardrail', name: 'guard.input', service: 'keel-sdk（ops-copilot 入口）', startMs: 15, durationMs: 30, inputSummary: '用户问题', outputSummary: '提示词注入检测未命中' }),
    n({ id: 'sup', agentKey: 'sup', type: 'agent', name: 'ops-copilot', service: 'ops-copilot', startMs: 45, durationMs: 10625, aggregated: true, tokens: 10578, costCny: 0.0214, inputSummary: 'WT-07 告警频繁，查同批次故障率，给检修建议，需要时建工单', outputSummary: '同批次 F203 故障率 18%，是全场平均的 3 倍；给出 4 条检修建议，已创建工单 WO-20260929-031', gateNote: '评测：ops-copilot v0.2.0 门禁 0.87 通过' }),
    n({ id: 'plan', agentKey: 'sup', type: 'generation', name: 'plan', shortName: '规划', service: 'ops-copilot', startMs: 45, durationMs: 1405, depth: 1, criticalPath: true, model: 'qwen-plus', llmKeyAlias: 'ops-copilot-staging', tokensIn: 860, tokensOut: 142, costCny: 0.0021, inputSummary: '用户问题 + manifest 中声明的可委派智能体（askdb、offshore-wind）', outputSummary: '拆成两个子任务并行执行：① askdb 查同批次故障率；② offshore-wind 给处理建议' }),
    n({ id: 'askdb', agentKey: 'askdb', type: 'agent', name: 'askdb', service: 'askdb（跨服务）', startMs: 1480, durationMs: 3720, depth: 1, aggregated: true, tokens: 1850, costCny: 0.0036, inputSummary: '查询与 WT-07 同批次（2021-B3）风机近 90 天 F203 故障率', outputSummary: '返回 12 行：同批次 F203 故障率 18%，全场平均 6%', gateNote: '评测：askdb v1.9.0 门禁 0.88 通过' }),
    n({ id: 'a1', agentKey: 'askdb', type: 'retriever', name: 'schema_recall', shortName: 'schema 召回', service: 'askdb', startMs: 1500, durationMs: 400, depth: 2, inputSummary: '子任务文本', outputSummary: '命中表 turbines、alarms、work_orders' }),
    n({ id: 'a2', agentKey: 'askdb', type: 'generation', name: 'sql.generate', shortName: 'SQL 生成', service: 'askdb', startMs: 1900, durationMs: 2000, depth: 2, model: 'qwen-plus', llmKeyAlias: 'askdb-prod', tokensIn: 1640, tokensOut: 210, costCny: 0.0036, inputSummary: 'schema + 子任务', outputSummary: 'SQL 已生成（原文在审计回放中查看）' }),
    n({ id: 'a3', agentKey: 'askdb', type: 'tool', name: 'sql.execute', shortName: 'SQL 执行', service: 'askdb', startMs: 3900, durationMs: 700, depth: 2, inputSummary: '只读连接 dw_prod，风险中', outputSummary: '返回 12 行', auditIds: ['ae_…b7e2'] }),
    n({ id: 'a4', agentKey: 'askdb', type: 'guardrail', name: 'result.check', shortName: '结果校验', service: 'askdb', startMs: 4600, durationMs: 600, depth: 2, inputSummary: '结果集', outputSummary: '行数、统计口径校验通过' }),
    n({ id: 'wind', agentKey: 'wind', type: 'agent', name: 'offshore-wind', service: 'offshore-wind（跨服务）', startMs: 1480, durationMs: 4920, depth: 1, status: 'fallback', criticalPath: true, aggregated: true, tokens: 3950, costCny: 0.0105, inputSummary: 'WT-07 变桨系统 F203 频发，给出处理建议', outputSummary: '4 条处理建议，引用故障处理手册 §4.7、安全规程 §5.1', gateNote: '评测：offshore-wind v0.7.1 门禁 0.86 通过' }),
    n({ id: 'w1', agentKey: 'wind', type: 'generation', name: 'route', shortName: '路由', service: 'offshore-wind', startMs: 1500, durationMs: 700, depth: 2, criticalPath: true, model: 'qwen-flash', llmKeyAlias: 'offshore-wind-prod', tokensIn: 380, tokensOut: 30, costCny: 0.0004, inputSummary: '子任务文本', outputSummary: '需要：故障处理手册 + 安全规程' }),
    n({ id: 'w2', agentKey: 'wind', type: 'retriever', name: 'rag.fault-manual', shortName: '检索手册', service: 'rag-forge', startMs: 2200, durationMs: 700, depth: 2, criticalPath: true, inputSummary: 'F203 变桨', outputSummary: 'top5，命中 §4.7 变桨系统故障（改写 90ms · 向量 220ms · 关键词 70ms · 重排 280ms）', gateNote: 'rag-forge 知识库 fault-manual · recall@5 0.91' }),
    n({ id: 'w3', agentKey: 'wind', type: 'retriever', name: 'rag.safety-procedures', shortName: '检索规程', service: 'rag-forge', startMs: 2200, durationMs: 600, depth: 2, inputSummary: '登机检修', outputSummary: 'top3，命中 §5.1 停机作业' }),
    n({ id: 'w4', agentKey: 'wind', type: 'generation', name: 'answer', shortName: '生成回答', service: 'offshore-wind', startMs: 2950, durationMs: 3050, depth: 2, status: 'fallback', criticalPath: true, model: 'deepseek-v3', fallbackFrom: 'qwen-plus', llmKeyAlias: 'offshore-wind-prod', tokensIn: 2980, tokensOut: 560, costCny: 0.0101, inputSummary: '子任务 + 检索到的 8 个片段', outputSummary: 'qwen-plus 超时 5s，LiteLLM 按降级规则切到 deepseek-v3 后完成' }),
    n({ id: 'w5', agentKey: 'wind', type: 'guardrail', name: 'grounding', shortName: '接地校验', service: 'offshore-wind', startMs: 6000, durationMs: 400, depth: 2, criticalPath: true, inputSummary: '回答中的 6 条陈述', outputSummary: '6 条全部能在引用片段中找到依据' }),
    n({ id: 'syn', agentKey: 'sup', type: 'generation', name: 'synthesize', shortName: '汇总', service: 'ops-copilot', startMs: 6450, durationMs: 3350, depth: 1, criticalPath: true, model: 'qwen-plus', llmKeyAlias: 'ops-copilot-staging', tokensIn: 2310, tokensOut: 486, costCny: 0.0048, inputSummary: 'askdb 的统计结果 + offshore-wind 的处理建议', outputSummary: '结论：同批次故障率偏高 3 倍，建议整批排查；需要创建 P1 工单' }),
    n({ id: 'tool', agentKey: 'sup', type: 'tool', name: 'work_order_create', shortName: '建工单', service: 'ops-copilot', startMs: 9800, durationMs: 600, depth: 1, criticalPath: true, inputSummary: 'WT-07 · 变桨 F203 · P1 · 同批次排查', outputSummary: '工单 WO-20260929-031 已创建', auditIds: ['ae_…c31a'], approvalId: 'ap_0912' }),
    n({ id: 'appr', agentKey: 'human', type: 'event', name: 'approval · 李工', shortName: '人工审批', service: '审批中心', startMs: 9850, durationMs: 0, humanWaitLabel: '4m12s', depth: 2, inputSummary: '高风险工具调用 work_order_create', outputSummary: '班组长李工批准，用时 4 分 12 秒（不计入智能体耗时）', approvalId: 'ap_0912' }),
    n({ id: 'gout', agentKey: 'platform', type: 'guardrail', name: 'guard.output', shortName: '回答用户', service: 'keel-sdk（ops-copilot 出口）', startMs: 10400, durationMs: 250, criticalPath: true, model: 'qwen-flash', llmKeyAlias: 'keel-guard', tokensIn: 820, tokensOut: 160, costCny: 0.0004, inputSummary: '最终回答', outputSummary: 'PII 脱敏 0 处，流式输出到 11.2s 结束' }),
    n({ id: 'aud', agentKey: 'platform', type: 'event', name: 'audit.write', service: 'keel-audit', startMs: 10650, durationMs: 20, inputSummary: '本次调用产生的 3 条审计事件', outputSummary: '已写入，哈希链校验通过' }),
  ],
  edges: [
    { from: 'plan', to: 'askdb', label: '委派 ①', atMs: 1450, parallel: true },
    { from: 'plan', to: 'wind', label: '委派 ②', atMs: 1450, parallel: true },
    { from: 'askdb', to: 'syn', label: '12 行', atMs: 5200, parallel: false },
    { from: 'wind', to: 'syn', label: '4 条建议', atMs: 6400, parallel: false },
    { from: 'syn', to: 'tool', label: null, atMs: 9800, parallel: false },
    { from: 'tool', to: 'appr', label: '审批通过', atMs: 9850, parallel: false },
    { from: 'syn', to: 'gout', label: null, atMs: 10400, parallel: false },
  ],
}

const COLORS: Record<string, string> = {
  careermate: '#2ec4b6', askdb: '#5b9cf6', 'offshore-wind': '#34c38f', 'cs-bot': '#b48cf2', 'code-review': '#e36fae',
  'prd-agent': '#f1b44c', 'test-gen': '#6fd3e3',
}

const SINGLE: Omit<TraceSummary, 'traceId' | 'startedAt'>[] = [
  { question: '上季度华东区退货率最高的三个品类', rootAgent: 'askdb', env: 'prod', durationMs: 5840, tokens: 2210, costCny: 0.0041, status: 'ok' },
  { question: '帮我改一下简历里的项目经历', rootAgent: 'careermate', env: 'prod', durationMs: 3120, tokens: 1530, costCny: 0.0022, status: 'ok' },
  { question: '你们的退货政策是多少天？', rootAgent: 'cs-bot', env: 'prod', durationMs: 1460, tokens: 410, costCny: 0.0003, status: 'ok', cached: true },
  { question: '查一下所有员工的薪资明细', rootAgent: 'askdb', env: 'prod', durationMs: 410, tokens: 0, costCny: 0, status: 'failed', blocked: true },
  { question: 'order-service#142 帮我评审一下', rootAgent: 'code-review', env: 'prod', durationMs: 14800, tokens: 6820, costCny: 0.0118, status: 'ok' },
  { question: 'WT-11 偏航电机温度高怎么处理', rootAgent: 'offshore-wind', env: 'staging', durationMs: 8900, tokens: 3120, costCny: 0.0086, status: 'fallback' },
  { question: '把 REQ-231 拆成开发任务', rootAgent: 'prd-agent', env: 'staging', durationMs: 7600, tokens: 2650, costCny: 0.0031, status: 'ok' },
  { question: '给 PaymentService 补单测', rootAgent: 'test-gen', env: 'staging', durationMs: 42000, tokens: 9100, costCny: 0.0094, status: 'failed' },
]

const singleTraces: TraceSummary[] = Array.from({ length: 24 }, (_, i) => {
  const base = SINGLE[i % SINGLE.length]
  return {
    ...base,
    traceId: i === 0 ? 'tr_4f02aa18' : `tr_${(0x3b10c0 + i * 7919).toString(16)}`,
    startedAt: at(20 + i * 23),
    sessionId: `s_${(0x2a00 + i * 37).toString(16)}`,
    turn: 1 + (i % 3),
    userId: `u_${2000 + i * 13}`,
    userRole: null,
    agents: [base.rootAgent!],
    multiAgent: false,
    humanWaitMs: null,
    blocked: base.blocked ?? false,
    cached: base.cached ?? false,
  }
})

export const traceSummaries: TraceSummary[] = [multiAgent.summary!, ...singleTraces]

function singleDetail(summary: TraceSummary): TraceDetail {
  const agent = summary.rootAgent!
  const total = summary.durationMs!
  const root: Agent = { key: 'root', name: agent, subtitle: '单智能体', color: COLORS[agent] ?? '#8a97ab', kind: 'supervisor' }
  if (summary.blocked) {
    return {
      summary,
      langfuseUrl: `${LANGFUSE}/${summary.traceId}`,
      agents: [PLATFORM],
      latencyBreakdown: [{ label: '准入与护栏', ms: total, agentKey: 'platform' }],
      costBreakdown: [],
      nodes: [
        n({ id: 'gw', agentKey: 'platform', type: 'event', name: 'gateway.auth', service: 'keel-gateway', startMs: 0, durationMs: 20, inputSummary: 'Bearer JWT', outputSummary: '准入通过' }),
        n({ id: 'gin', agentKey: 'platform', type: 'guardrail', name: 'guard.input', shortName: '输入护栏', service: 'keel-sdk', startMs: 20, durationMs: total - 20, status: 'failed', criticalPath: true, inputSummary: '用户问题', outputSummary: '命中敏感数据规则：个人薪资明细，请求已拦截' }),
      ],
      edges: [],
    }
  }
  const t = (p: number) => Math.round(total * p)
  return {
    summary,
    langfuseUrl: `${LANGFUSE}/${summary.traceId}`,
    agents: [PLATFORM, root],
    latencyBreakdown: [
      { label: agent, ms: t(0.94), agentKey: 'root' },
      { label: '平台与输出', ms: total - t(0.94), agentKey: 'platform' },
    ],
    costBreakdown: [{ agentKey: 'root', costCny: summary.costCny }],
    nodes: [
      n({ id: 'gw', agentKey: 'platform', type: 'event', name: 'gateway.auth', service: 'keel-gateway', startMs: 0, durationMs: t(0.02), inputSummary: 'Bearer JWT', outputSummary: `路由到 ${agent}` }),
      n({ id: 'root', agentKey: 'root', type: 'agent', name: agent, service: agent, startMs: t(0.02), durationMs: t(0.94), aggregated: true, status: summary.status, tokens: summary.tokens, costCny: summary.costCny, inputSummary: summary.question!, outputSummary: summary.status === 'failed' ? '执行失败' : '已回答' }),
      n({ id: 'ret', agentKey: 'root', type: 'retriever', name: 'rag.search', shortName: '检索', service: 'rag-forge', startMs: t(0.03), durationMs: t(0.18), depth: 1, criticalPath: true, inputSummary: '改写后的问题', outputSummary: 'top5 片段' }),
      n({ id: 'gen', agentKey: 'root', type: 'generation', name: 'answer', shortName: '生成回答', service: agent, startMs: t(0.22), durationMs: t(0.7), depth: 1, criticalPath: true, status: summary.status, model: summary.status === 'fallback' ? 'deepseek-v3' : 'qwen-plus', fallbackFrom: summary.status === 'fallback' ? 'qwen-plus' : null, llmKeyAlias: `${agent}-${summary.env}`, tokens: summary.tokens, costCny: summary.costCny, inputSummary: '问题 + 检索片段', outputSummary: summary.status === 'failed' ? '上游超时' : '回答已生成' }),
      n({ id: 'gout', agentKey: 'platform', type: 'guardrail', name: 'guard.output', shortName: '回答用户', service: 'keel-sdk', startMs: t(0.96), durationMs: total - t(0.96), criticalPath: true, inputSummary: '最终回答', outputSummary: 'PII 脱敏 0 处' }),
    ],
    edges: [
      { from: 'ret', to: 'gen', label: null, atMs: t(0.22), parallel: false } satisfies Edge,
      { from: 'gen', to: 'gout', label: null, atMs: t(0.96), parallel: false } satisfies Edge,
    ],
  }
}

export function traceDetail(traceId: string): TraceDetail | null {
  if (traceId === MULTI_ID) return multiAgent
  const summary = singleTraces.find((s) => s.traceId === traceId)
  return summary ? singleDetail(summary) : null
}
