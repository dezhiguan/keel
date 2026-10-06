import type { SharedServices } from '@/api/services'

const daysAgo = (d: number) => new Date(Date.now() - d * 86_400_000).toISOString()

export const sharedServices: SharedServices = {
  services: [
    { name: 'keel-gateway', role: '入口网关 · 自研轻量版', instances: '2/2', p95: '18ms 开销', errorRate: '0.1%', status: 'ONLINE' },
    { name: 'keel-server', role: '注册中心 · 工具 · 审批', instances: '2/2', p95: '42ms', errorRate: '0%', status: 'ONLINE' },
    { name: 'keel-audit', role: '审计', instances: '2/2', p95: '9ms', errorRate: '0%', status: 'ONLINE' },
    { name: 'auth-gateway', role: '身份认证', instances: '2/2', p95: '25ms', errorRate: '0%', status: 'ONLINE' },
    { name: 'rag-forge', role: '知识检索', instances: '2/2 · worker 1', p95: '0.78s', errorRate: '0.6%（429）', status: 'ONLINE' },
    { name: '薄网关', role: '模型网关', instances: '2/2', p95: '12ms 开销', errorRate: '0.2%', status: 'ONLINE' },
    { name: 'Langfuse', role: '追踪 · 评测（Cloud 日本）', instances: '云端', p95: null, status: 'ONLINE' },
  ],
  ragforge: {
    kpi: {
      searches24h: 9870,
      searchTrendPct: 5.4,
      p95Seconds: 0.78,
      p50Seconds: 0.7,
      throttleRate: 0.006,
      kbCount: 6,
      staleKbCount: 1,
      modelCostCny: 4.1,
    },
    stageLatency: [
      { stage: 'rewrite', p50Ms: 90 },
      { stage: 'vector', p50Ms: 210 },
      { stage: 'keyword', p50Ms: 80 },
      { stage: 'rerank', p50Ms: 260 },
      { stage: 'other', p50Ms: 60 },
    ],
    callers: [
      { agent: 'offshore-wind', calls: 3120 },
      { agent: 'careermate', calls: 2860 },
      { agent: 'cs-bot', calls: 2410 },
      { agent: 'askdb', calls: 1480 },
      { agent: 'code-review', calls: 412 },
    ],
    knowledgeBases: [
      { kb: 'fault-manual', owner: '电力运维组', documents: 312, updatedAt: daysAgo(2), searches24h: 2040, zeroHitRate: 0.012, recallAt5: 0.91, stale: false },
      { kb: 'safety-procedures', owner: '电力运维组', documents: 86, updatedAt: daysAgo(14), searches24h: 1080, zeroHitRate: 0.008, recallAt5: 0.94, stale: false },
      { kb: 'jd-kb', owner: '求职产品组', documents: 5420, updatedAt: daysAgo(0), searches24h: 2860, zeroHitRate: 0.035, recallAt5: 0.86, stale: false },
      { kb: 'cs-faq', owner: '客服中心', documents: 640, updatedAt: daysAgo(41), searches24h: 2410, zeroHitRate: 0.068, recallAt5: 0.79, stale: true },
      { kb: 'metric-glossary', owner: '数据平台组', documents: 210, updatedAt: daysAgo(5), searches24h: 1480, zeroHitRate: 0.021, recallAt5: 0.88, stale: false },
      { kb: 'dev-standards', owner: '研发效能组', documents: 148, updatedAt: daysAgo(3), searches24h: 469, zeroHitRate: 0.016, recallAt5: 0.9, stale: false },
    ],
  },
}
