import { get, post } from './http'
import type { components, operations } from './schema'

export type AuditEvent = components['schemas']['AuditEvent']
export type ListAuditQuery = NonNullable<operations['listAuditEvents']['parameters']['query']>
export type AuditPage = components['schemas']['PageMeta'] & { items?: AuditEvent[] }
type VerifyBody = operations['verifyAuditChain']['responses']['200']['content']['application/json']
export type VerifyResult = NonNullable<VerifyBody['data']>

export function listAuditEvents(query: ListAuditQuery) {
  return get<AuditPage>('/audit/events', { params: query })
}

export function verifyAuditChain(agent?: string) {
  return post<VerifyResult>('/audit/verify', undefined, { params: { agent } })
}

export function requestAuditExport(filter: Record<string, unknown>, reason: string) {
  return post<{ exportId?: string; approvalId?: string }>('/audit/exports', { filter, reason })
}
