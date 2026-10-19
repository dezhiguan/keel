import { get, post } from './http'
import type { components, operations } from './schema'

export type EvalResult = components['schemas']['EvalResult']
export type EvalGateRow = NonNullable<components['schemas']['EvalGateBoard']['rows']>[number]
type EvalRunBody = operations['getEvalRun']['responses']['200']['content']['application/json']
export type EvalRun = NonNullable<EvalRunBody['data']>

export function getEvalGates(env: string = 'all') {
  return get<{ rows?: EvalGateRow[] }>('/eval/gates', { params: { env } })
}

export function getLatestEval(agent: string) {
  return get<EvalResult>(`/eval/${encodeURIComponent(agent)}/latest`)
}

export function runEval(agent: string) {
  return post<{ runId?: string }>(`/eval/${encodeURIComponent(agent)}/runs`)
}

export function getEvalRun(runId: string) {
  return get<EvalRun>(`/eval/runs/${encodeURIComponent(runId)}`)
}

export function getActiveEvalRun(agent: string) {
  return get<{ runId?: string } | null>(`/eval/${encodeURIComponent(agent)}/runs/active`, { params: { includeFailed: true } })
}

export function publishEvaluatedAgent(agent: string, runId: string, env: 'staging' | 'prod') {
  return post<components['schemas']['ReleaseRecord']>(`/eval/${encodeURIComponent(agent)}/runs/${encodeURIComponent(runId)}/publish`, { env })
}
