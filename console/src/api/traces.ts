import { get } from './http'
import type { components, operations } from './schema'

export type TraceSummary = components['schemas']['TraceSummary']
export type TraceDetail = components['schemas']['TraceDetail']
export type TraceNode = components['schemas']['TraceNode'] & {
  promptName?: string | null
  promptVersion?: number | null
  promptFallback?: boolean
}
export type ListTracesQuery = NonNullable<operations['listTraces']['parameters']['query']>
export type TracePage = components['schemas']['PageMeta'] & { items?: TraceSummary[]; langfuseUrl?: string; dataStatus?: components['schemas']['InsightDataStatus'] }

export function listTraces(query: ListTracesQuery, signal?: AbortSignal) {
  return get<TracePage>('/insight/traces', { params: query, signal, timeout: 10000 })
}

export function getTrace(traceId: string) {
  return get<TraceDetail>(`/insight/traces/${encodeURIComponent(traceId)}`)
}
