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
