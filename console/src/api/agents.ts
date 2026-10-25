import { KeelApiError, del, get, newTraceparent, post, put, toKeelError } from './http'
import { takeSseFrames, type SseFrame } from '@/views/agents/debug'
import type { components, operations } from './schema'

export type AgentSummary = components['schemas']['AgentSummary']
export type AgentStatus = components['schemas']['AgentStatus']
export type ListAgentsQuery = NonNullable<operations['listAgents']['parameters']['query']>
export type AgentPage = components['schemas']['PageMeta'] & { items?: AgentSummary[] }

export function listAgents(query: ListAgentsQuery) {
  return get<AgentPage>('/agents', { params: { usage: false, ...query } })
}

export function getAgentUsage(env: string) {
  return get<{ items?: AgentSummary[] }>('/insight/agent-usage', { params: { env } })
}

/** Registry row first, usage numbers when the separate request returns. */
export function mergeUsage<T extends AgentSummary>(agent: T, usage?: AgentSummary | null): T {
  if (!usage) return agent
  return {
    ...agent,
    calls24h: usage.calls24h,
    callsTotal: usage.callsTotal,
    p95Seconds: usage.p95Seconds,
    costCny: usage.costCny,
    dailyBudgetCny: usage.dailyBudgetCny ?? agent.dailyBudgetCny,
    score: usage.score,
    gatePassed: usage.score == null ? agent.gatePassed : usage.score >= 0.85,
  }
}

export type AgentDetail = components['schemas']['AgentDetail']

export function getAgent(name: string, includeRuntimeEvidence?: boolean) {
  return get<AgentDetail>(`/agents/${encodeURIComponent(name)}`, { params: { includeRuntimeEvidence } })
}

export function getAgentElements(name: string, signal?: AbortSignal) {
  return get<components['schemas']['AgentElementEvidence']>(`/agents/${encodeURIComponent(name)}/elements`, { signal, timeout: 10_000 })
}

export function recheckAgent(name: string) {
  return post<components['schemas']['SelfCheckReport']>(`/agents/${encodeURIComponent(name)}/self-check`, undefined, { timeout: 360_000 })
}

export interface SelfCheckReport {
  passed?: boolean
  items?: { name?: string; passed?: boolean; detail?: string | null }[]
}

export interface CatalogTool {
  name?: string
  description?: string
  access?: 'READ' | 'WRITE' | 'EXEC'
  risk?: 'LOW' | 'MID' | 'HIGH'
  status?: string
}

export interface CatalogPolicy {
  id?: string
  name?: string
}

export interface CatalogKnowledgeBase {
  kb?: string
  owner?: string
}

export function getCatalog() {
  return get<{ tools?: CatalogTool[]; approvalPolicies?: CatalogPolicy[]; knowledgeBases?: CatalogKnowledgeBase[] }>('/catalog')
}

export function checkAgentName(name: string) {
  return get<{ available?: boolean; reason?: string }>('/agents/name-check', { params: { name } })
}

export function previewManifest(body: Record<string, unknown>) {
  return post<{ yaml: string; warnings?: string[] }>('/agents/manifest-preview', body)
}

export function registerAgent(body: Record<string, unknown>) {
  return post<SelfCheckReport>('/agents', body)
}

export function retireAgent(name: string, env: 'dev' | 'test' | 'staging' | 'prod') {
  return post<{ name?: string; status?: string }>(`/agents/${encodeURIComponent(name)}/retire`, { env })
}

export function deleteAgent(name: string) {
  return del<unknown>(`/agents/${encodeURIComponent(name)}`)
}

export function getAgentGuardrails(name: string) {
  return get<import('@/views/agents/guard').GuardPage>(`/agents/${encodeURIComponent(name)}/guardrails`)
}

export function saveAgentGuardrails(name: string, modes: Record<string, string>) {
  return put<import('@/views/agents/guard').GuardPage>(`/agents/${encodeURIComponent(name)}/guardrails`, modes)
}

export function checkAgentGuard(name: string, source: string, text: string) {
  return get<import('@/views/agents/guard').GuardCheckResult>(`/agents/${encodeURIComponent(name)}/guard/check`, { params: { source, text } })
}

export function chatWithAgent(name: string, text: string, sessionId?: string, signal?: AbortSignal) {
  return post<{ text?: string; traceId?: string | null }>(`/agents/${encodeURIComponent(name)}/chat`, { text, ...(sessionId ? { sessionId } : {}) }, { timeout: 190_000, signal })
}

export interface DebugSessionSummary {
  id: string
  title: string
  messageCount: number
  updatedAt: string
}

export interface DebugMessageRow {
  id: number
  role: 'user' | 'agent'
  text: string
  traceId?: string | null
}

export function listDebugSessions(name: string, cursor?: string | null) {
  return get<{ items?: DebugSessionSummary[]; total?: number; nextCursor?: string | null; retention?: string; crossSessionHistory?: boolean }>(
    `/agents/${encodeURIComponent(name)}/sessions`,
    { params: cursor ? { cursor } : {} },
  )
}

export function createDebugSession(name: string) {
  return post<DebugSessionSummary>(`/agents/${encodeURIComponent(name)}/sessions`, {})
}

export function deleteDebugSession(name: string, id: string) {
  return del<{ id?: string }>(`/agents/${encodeURIComponent(name)}/sessions/${encodeURIComponent(id)}`)
}

export function listDebugMessages(name: string, id: string, before?: number | null) {
  return get<{ items?: DebugMessageRow[]; nextCursor?: number | null }>(
    `/agents/${encodeURIComponent(name)}/sessions/${encodeURIComponent(id)}/messages`,
    { params: before ? { before } : {} },
  )
}

export async function streamAgentChat(
  name: string,
  text: string,
  sessionId: string,
  signal: AbortSignal,
  onFrame: (frame: SseFrame) => void,
) {
  let response: Response
  try {
    response = await fetch(`/api/v1/agents/${encodeURIComponent(name)}/chat/stream`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        traceparent: newTraceparent(),
        'X-Keel-Console': '1',
      },
      body: JSON.stringify({ text, sessionId, requestId: crypto.randomUUID() }),
      signal,
    })
  } catch (error) {
    if (signal.aborted) throw error
    throw toKeelError(error)
  }
  if (!response.ok || !response.body) {
    let payload: { code?: string; message?: string; traceId?: string; retryable?: boolean } = {}
    try {
      payload = await response.json()
    } catch {
      payload = {}
    }
    throw new KeelApiError(payload.code || 'NETWORK_ERROR', payload.message || '调用失败', payload.traceId, payload.retryable ?? false)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let rest = ''
  while (true) {
    const chunk = await reader.read()
    if (chunk.done) break
    rest += decoder.decode(chunk.value, { stream: true })
    const taken = takeSseFrames(rest)
    rest = taken.rest
    for (const frame of taken.frames) onFrame(frame)
  }
}
