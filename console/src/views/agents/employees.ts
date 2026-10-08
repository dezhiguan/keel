export type Layer = 'meta' | 'dev' | 'biz'

export type EmployeeCard = {
  name: string
  displayName: string
  template: string
  layer: Layer
  color: string
  description: string
  source: string
  jobId?: string
  owner: string
  calls: number | null
  score: number | null
  budget: number | null
  cost: number | null
  status?: string
  placeholder: boolean
}

type Seed = Omit<EmployeeCard, 'placeholder'>

/** Prototype employees that are not in the registry yet. Real agents with the same id replace these numbers. */
const CATALOG: Seed[] = [
  { name: 'meta-agent', displayName: '元智能体', template: 'graph-agent', layer: 'meta', color: '#ff7a45', description: '生产和升级研发数字员工；只由人修改。', source: '人工编写', owner: 'amy', calls: 640, score: 0.82, budget: 50, cost: 61, status: 'ONLINE' },
  { name: 'dev-lead', displayName: '研发组长', template: 'supervisor', layer: 'dev', color: '#f1b44c', description: '接收业务员工的生产任务（单个或批量），按阶段委派，管预算、修复轮次、关口和人工接管。', source: 'meta-agent · DF-0003', jobId: 'DF-0003', owner: 'amy', calls: 1284, score: 0.89, budget: 30, cost: 96, status: 'ONLINE' },
  { name: 'spec-agent', displayName: '需求分析师', template: 'graph-agent', layer: 'dev', color: '#e8c26a', description: '需求表单 → 需求单、agent.yaml 草稿、授权清单；批量时找出重复需求。', source: 'meta-agent · DF-0002', jobId: 'DF-0002', owner: 'amy', calls: 412, score: 0.84, budget: 20, cost: 38, status: 'ONLINE' },
  { name: 'dev-agent', displayName: '开发工程师', template: 'graph-agent', layer: 'dev', color: '#5b9cf6', description: '按模板生成项目，沙箱验证后开 PR；协作模式下以人的提交为基线。', source: 'meta-agent · DF-0004', jobId: 'DF-0004', owner: 'amy', calls: 2956, score: 0.78, budget: 50, cost: 215, status: 'ONLINE' },
  { name: 'eval-agent', displayName: '测试工程师', template: 'tool-agent', layer: 'dev', color: '#34c38f', description: '在人给的种子用例上扩充边界、对抗、注入用例；看不到隐藏考题。', source: 'meta-agent · DF-0005', jobId: 'DF-0005', owner: 'amy', calls: 388, score: 0.83, budget: 20, cost: 31, status: 'ONLINE' },
  { name: 'code-review', displayName: '代码评审员', template: 'tool-agent', layer: 'dev', color: '#e36fae', description: '原计划 P3 手写，改由元智能体生产。评审包括人写提交在内的全部改动。', source: 'meta-agent · DF-0006', jobId: 'DF-0006', owner: 'amy', calls: 301, score: 0.86, budget: 30, cost: 16, status: 'ONLINE' },
  { name: 'ci-doctor', displayName: 'CI 诊断员', template: 'tool-agent', layer: 'dev', color: '#f46a6a', description: '原计划 P3 手写，改由元智能体生产。按维度定位门禁退步原因。', source: 'meta-agent · DF-0013', jobId: 'DF-0013', owner: 'amy', calls: 117, score: 0.85, budget: 20, cost: 4, status: 'ONLINE' },
  { name: 'release-agent', displayName: '发布工程师', template: 'tool-agent', layer: 'dev', color: '#b48cf2', description: '部署 PR 分支、跑门禁；申请合并 PR（高风险，等发布审批）；上线后观察 7 天。', source: 'meta-agent · DF-0007', jobId: 'DF-0007', owner: 'amy', calls: 534, score: 0.91, budget: 15, cost: 13, status: 'DEGRADED' },
  { name: 'prd-agent', displayName: '需求分析（通用）', template: 'java-spring', layer: 'dev', color: '#c9a35a', description: '现有研发智能体，面向通用业务代码，不属于研发流水线。', source: '人工迁移（现有）', owner: 'amy', calls: 86, score: 0.83, budget: 20, cost: 1.9, status: 'ONLINE' },
  { name: 'dev-copilot', displayName: '研发总控（通用）', template: 'java-spring', layer: 'dev', color: '#ffb08f', description: '现有研发智能体（P3 从 ai-multi-agent-dev-platform 拆出）。', source: '人工迁移（现有）', owner: 'amy', calls: 0, score: 0, budget: 30, cost: 0, status: 'REGISTERED' },
  { name: 'careermate', displayName: '小职 · 求职助手', template: 'java-spring', layer: 'biz', color: '#2ec4b6', description: '现有业务智能体，照常由人维护；也可以发起改造任务。', source: '人工编写（现有）', owner: '王工', calls: 5210, score: 0.91, budget: 60, cost: 31.2, status: 'ONLINE' },
  { name: 'askdb', displayName: '问数', template: 'graph-agent', layer: 'biz', color: '#8fb6ff', description: '现有业务智能体。', source: '人工编写（现有）', owner: '刘工', calls: 3904, score: 0.88, budget: 40, cost: 28.8, status: 'DEGRADED' },
  { name: 'ticket-triage', displayName: '工单分派员', template: 'tool-agent', layer: 'biz', color: '#4fd1c5', description: '由研发流水线生产的业务员工。', source: 'dev-lead · DF-0012', jobId: 'DF-0012', owner: '陈主管', calls: 2210, score: 0.88, budget: 20, cost: 9.8, status: 'ONLINE' },
  { name: 'meeting-minutes', displayName: '会议纪要员', template: 'tool-agent', layer: 'biz', color: '#6fd3e3', description: '由研发流水线生产的业务员工，上线观察第 3 天。', source: 'dev-lead · DF-0014', jobId: 'DF-0014', owner: '陈主管', calls: 318, score: 0.88, budget: 20, cost: 4.4, status: 'ONLINE' },
]

export type RegistryAgent = {
  name?: string
  displayName?: string
  category?: string | null
  layer?: string | null
  template?: string | null
  language?: string | null
  env?: string | null
  version?: string | null
  status?: string
  ownerUser?: string | null
  ownerOrg?: string | null
  calls24h?: number | null
  costCny?: number | null
  dailyBudgetCny?: number | null
  score?: number | null
  devflowJobId?: string | null
}

const LAYER_FROM_API: Record<string, Layer> = { META: 'meta', DEV: 'dev', BIZ: 'biz', meta: 'meta', dev: 'dev', biz: 'biz' }

export function layerOf(agent: RegistryAgent): Layer {
  if (agent.layer && LAYER_FROM_API[agent.layer]) return LAYER_FROM_API[agent.layer]
  if (agent.name === 'meta-agent') return 'meta'
  return agent.category === 'dev' ? 'dev' : 'biz'
}

export function employeeByName(name: string): EmployeeCard | undefined {
  const item = CATALOG.find((row) => row.name === name)
  return item ? { ...item, placeholder: true } : undefined
}

export function mergeEmployees(agents: RegistryAgent[]): EmployeeCard[] {
  const byName = new Map(CATALOG.map((item) => [item.name, { ...item, placeholder: true }]))
  const extras: EmployeeCard[] = []
  for (const agent of agents) {
    if (!agent.name) continue
    const known = byName.get(agent.name)
    const card = fromRegistry(agent, known)
    if (known) byName.set(agent.name, card)
    else extras.push(card)
  }
  return [...byName.values(), ...extras]
}

function fromRegistry(agent: RegistryAgent, known?: EmployeeCard): EmployeeCard {
  const layer = known?.layer ?? layerOf(agent)
  const jobId = agent.devflowJobId || known?.jobId
  const source = agent.devflowJobId
    ? `${layer === 'dev' ? 'meta-agent' : 'dev-lead'} · ${agent.devflowJobId}`
    : known?.source ?? '人工编写（现有）'
  return {
    name: agent.name || '',
    displayName: agent.displayName || known?.displayName || agent.name || '',
    template: agent.template || known?.template || agent.language || '—',
    layer,
    color: known?.color ?? '#8a97ab',
    description: known?.description ?? '已在注册中心登记的智能体。',
    source,
    jobId,
    owner: agent.ownerUser || known?.owner || '—',
    calls: agent.calls24h ?? known?.calls ?? null,
    score: agent.score ?? known?.score ?? null,
    budget: agent.dailyBudgetCny ?? known?.budget ?? null,
    cost: agent.costCny ?? known?.cost ?? null,
    status: agent.status || known?.status,
    placeholder: false,
  }
}

export function lineageNodes(cards: EmployeeCard[]) {
  const meta = cards.find((card) => card.name === 'meta-agent')
  const produced = cards.filter((card) => card.layer === 'dev' && card.source.startsWith('meta-agent'))
  const piped = cards.filter((card) => card.layer === 'biz' && card.source.startsWith('dev-lead')).map((card) => card.name)
  const manual = cards.filter((card) => card.layer !== 'meta' && card.source.includes('人工')).map((card) => card.name)
  return { meta, produced, piped, manual }
}

export function cannotChange(layer: Layer) {
  if (layer === 'meta') return '只由人修改'
  if (layer === 'dev') return '改造只能由 meta-agent 发起'
  return '改造由研发流水线或人执行'
}
