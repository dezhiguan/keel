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

/** 原型《全流程智能体化控制台》卡片左上角角标色，字色为 #0a101a。 */
const BADGE_COLORS: Record<string, string> = {
  'meta-agent': '#ff7a45',
  'dev-lead': '#f1b44c',
  'spec-agent': '#e8c26a',
  'dev-agent': '#5b9cf6',
  'eval-agent': '#34c38f',
  'code-review': '#e36fae',
  'ci-doctor': '#f46a6a',
  'release-agent': '#b48cf2',
  'prd-agent': '#c9a35a',
  'dev-copilot': '#ffb08f',
  careermate: '#2ec4b6',
  askdb: '#8fb6ff',
  'ticket-triage': '#4fd1c5',
  'meeting-minutes': '#6fd3e3',
}

const BADGE_PALETTE = Object.values(BADGE_COLORS)

/** 名单里的智能体用原型色；其余按名字稳定取色，避免全部落成灰色。 */
export function badgeColor(name = '') {
  const named = BADGE_COLORS[name]
  if (named) return named
  let hash = 0
  for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0
  return BADGE_PALETTE[hash % BADGE_PALETTE.length]
}

export function layerOf(agent: RegistryAgent): Layer {
  return agentType(agent) ?? (agent.category === 'dev' ? 'dev' : 'biz')
}

/** 总览「类型」列：元智能体单独一档；分不出来留空，不猜成业务。 */
export function agentType(agent: { name?: string | null; category?: string | null; layer?: string | null }): Layer | null {
  const layer = agent.layer ? LAYER_FROM_API[agent.layer] : undefined
  if (layer === 'meta' || agent.name === 'meta-agent') return 'meta'
  if (layer === 'dev' || layer === 'biz') return layer
  if (agent.category === 'dev' || agent.category === 'biz') return agent.category
  return null
}

export function mergeEmployees(agents: RegistryAgent[]): EmployeeCard[] {
  return agents.filter((agent) => agent.name).map((agent) => fromRegistry(agent))
}

function fromRegistry(agent: RegistryAgent): EmployeeCard {
  const layer = layerOf(agent)
  const jobId = agent.devflowJobId || undefined
  const source = jobId
    ? `${layer === 'dev' ? 'meta-agent' : 'dev-lead'} · ${jobId}`
    : '人工编写（现有）'
  return {
    name: agent.name || '',
    displayName: agent.displayName || agent.name || '',
    template: agent.template || agent.language || '—',
    layer,
    color: badgeColor(agent.name),
    description: '已在注册中心登记的智能体。',
    source,
    jobId,
    owner: agent.ownerUser || '—',
    calls: agent.calls24h ?? null,
    score: agent.score ?? null,
    budget: agent.dailyBudgetCny ?? null,
    cost: agent.costCny ?? null,
    status: agent.status,
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
