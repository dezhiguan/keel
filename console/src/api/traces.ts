import { get } from './http'
import type { components, operations } from './schema'

export type TraceSummary = components['schemas']['TraceSummary']
export type TraceDetail = components['schemas']['TraceDetail']
export type TraceNode = components['schemas']['TraceNode']
export type ListTracesQuery = NonNullable<operations['listTraces']['parameters']['query']>
export type TracePage = components['schemas']['PageMeta'] & { items?: TraceSummary[]; langfuseUrl?: string }

export function listTraces(query: ListTracesQuery) {
  return get<TracePage>('/insight/traces', { params: query })
}

export function getTrace(traceId: string) {
  return get<TraceDetail>(`/insight/traces/${encodeURIComponent(traceId)}`)
}
