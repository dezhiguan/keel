import type { ModelGateway } from '@/api/models'

type Key = NonNullable<ModelGateway['keys']>[number]

const key = (agent: string, env: Key['env'], models: string[], spentCny: number, dailyBudgetCny: number): Key => ({
  alias: `${agent}-${env}`,
  agent,
  env,
  models,
  spentCny,
  dailyBudgetCny,
  status: 'ACTIVE',
})

export const modelGateway: ModelGateway = {
  models: [
    { model: 'qwen-plus', provider: 'DashScope', role: '主力', calls: 8920, p95: '4.6s', errorRate: 0.032, costCny: 58.3, status: 'warn', priceConfigured: true },
    { model: 'qwen-flash', provider: 'DashScope', role: '路由 / 意图', calls: 11960, p95: '0.8s', errorRate: 0.001, costCny: 6.4, status: 'ok', priceConfigured: true },
    { model: 'deepseek-v3', provider: 'DeepSeek', role: '降级备选', calls: 1204, p95: '5.9s', errorRate: 0.004, costCny: 9.8, status: 'ok', priceConfigured: true },
    { model: 'text-embedding-v3', provider: 'DashScope', role: '向量化（rag-forge）', calls: 14300, p95: '0.2s', errorRate: 0, costCny: 2.9, status: 'ok', priceConfigured: true },
  ],
  keys: [
    key('careermate', 'prod', ['qwen-plus', 'deepseek-v3'], 31.2, 60),
    key('askdb', 'prod', ['qwen-plus', 'deepseek-v3', 'qwen-flash'], 28.75, 40),
    key('offshore-wind', 'staging', ['qwen-plus', 'deepseek-v3', 'qwen-flash'], 7.9, 50),
    key('cs-bot', 'prod', ['qwen-plus', 'qwen-flash'], 18.55, 50),
    key('ops-copilot', 'staging', ['qwen-plus'], 3.2, 30),
    key('prd-agent', 'staging', ['qwen-plus'], 1.9, 20),
    key('code-review', 'prod', ['qwen-plus', 'deepseek-v3'], 6.4, 30),
    key('test-gen', 'staging', ['qwen-plus'], 2.3, 20),
    key('dev-copilot', 'staging', ['qwen-plus', 'deepseek-v3'], 0.9, 40),
  ],
  routing: [
    { agent: 'askdb', default: 'qwen-plus', fallback: ['deepseek-v3'] },
    { agent: 'offshore-wind', default: 'qwen-plus', fallback: ['deepseek-v3'] },
  ],
}
