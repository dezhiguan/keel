import type { EvalResult } from '@/api/eval'

type Dim = NonNullable<EvalResult['dimensions']>[number]

const AGENTS: Record<string, { score: number; passed: boolean; minScore: number; prod: string; candidate: string }> = {
  careermate: { score: 0.91, passed: true, minScore: 0.85, prod: 'v2.4.0', candidate: 'v2.4.1' },
  askdb: { score: 0.88, passed: true, minScore: 0.85, prod: 'v1.8.3', candidate: 'v1.9.0' },
  'offshore-wind': { score: 0.84, passed: false, minScore: 0.85, prod: 'v0.7.1', candidate: 'v0.7.2' },
  'cs-bot': { score: 0.9, passed: true, minScore: 0.85, prod: 'r2', candidate: 'r3' },
  'ops-copilot': { score: 0.87, passed: true, minScore: 0.85, prod: 'v0.1.4', candidate: 'v0.2.0' },
  'prd-agent': { score: 0.83, passed: true, minScore: 0.8, prod: 'v0.2.2', candidate: 'v0.3.0' },
  'code-review': { score: 0.86, passed: true, minScore: 0.8, prod: 'v1.0.1', candidate: 'v1.0.2' },
  'test-gen': { score: 0.81, passed: true, minScore: 0.8, prod: 'v0.2.0', candidate: 'v0.2.1' },
  'dev-copilot': { score: 0.8, passed: true, minScore: 0.8, prod: 'v0.0.9', candidate: 'v0.1.0' },
}

const dim = (tag: string, cases: number, prodScore: number, candidateScore: number): Dim => {
  const deltaPt = Math.round((candidateScore - prodScore) * 100)
  return { tag, cases, prodScore, candidateScore, deltaPt, verdict: deltaPt < -2 ? 'EXCEEDED' : deltaPt < 0 ? 'TOLERATED' : 'IMPROVED' }
}

export const evalAgents = Object.keys(AGENTS)

export function latestEval(agent: string): EvalResult | null {
  const a = AGENTS[agent]
  if (!a) return null
  const dimensions =
    agent === 'offshore-wind'
      ? [dim('故障判断准确', 64, 0.89, 0.88), dim('引用接地', 52, 0.92, 0.91), dim('拒答 / 无法确认', 28, 0.95, 0.97), dim('安全规程合规', 42, 0.8, 0.74)]
      : [dim('主要场景', 80, a.score + 0.01, a.score), dim('边界用例', 30, a.score - 0.02, a.score - 0.01), dim('安全拒答', 20, 0.95, 0.96)]
  return {
    agent,
    dataset: `${agent}/main`,
    caseCount: dimensions.reduce((n, d) => n + (d.cases ?? 0), 0),
    prodVersion: a.prod,
    candidateVersion: a.candidate,
    scoreTotal: a.score,
    passed: a.passed,
    gate: { minScore: a.minScore, maxRegression: 2, byTag: true },
    reason: a.passed ? null : '「安全规程合规」下降 6pt',
    ranAt: new Date(Date.now() - 3_600_000).toISOString(),
    dimensions,
    newFailures: [],
    ...(agent === 'code-review'
      ? { devflowJobId: 'DF-0008', holdout: { score: 0.72, minScore: 0.85, cases: 9 } }
      : {}),
  }
}
