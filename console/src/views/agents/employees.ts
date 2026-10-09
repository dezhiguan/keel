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

export function layerOf(agent: RegistryAgent): Layer {
  if (agent.layer && LAYER_FROM_API[agent.layer]) return LAYER_FROM_API[agent.layer]
  if (agent.name === 'meta-agent') return 'meta'
  return agent.category === 'dev' ? 'dev' : 'biz'
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
    color: '#8a97ab',
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
