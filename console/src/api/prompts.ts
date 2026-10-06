import { get, post } from './http'
import type { EnvFilter, PromptDetailModel, PromptState } from '@/views/prompts/promptView'

export interface PromptSummary {
  agent: string
  name: string
  type: string
  declared?: boolean
  summary?: string | null
  updatedAt?: string | null
  updatedBy?: string | null
  gate?: string
  gateNote?: string | null
  drift?: string[]
  codeVersion?: { version: number; gitSha?: string | null } | null
  envs?: Record<string, { version?: number | null; gate?: string | null }>
  states?: PromptState[]
}

export interface PromptList {
  langfuseUrl?: string | null
  items?: PromptSummary[]
}

export interface PromptBody {
  version: number
  type: string
  prompt: string | { role: string; content: string }[]
  config?: Record<string, number | string>
  variables?: string[]
}

export interface PromptDiff {
  added?: number
  removed?: number
  lines?: { op: 'same' | 'add' | 'del'; text: string }[]
}

export function listPrompts(env: EnvFilter, agent?: string) {
  return get<PromptList>('/prompts', { params: { env, agent: agent || undefined } })
}

export function getPrompt(agent: string, name: string) {
  return get<PromptDetailModel>(`/agents/${encodeURIComponent(agent)}/prompts/${encodeURIComponent(name)}`)
}

export function getPromptVersion(agent: string, name: string, version: number) {
  return get<PromptBody>(`/agents/${encodeURIComponent(agent)}/prompts/${encodeURIComponent(name)}/versions/${version}`)
}

export function diffPrompt(agent: string, name: string, from: number, to: number) {
  return get<PromptDiff>(`/agents/${encodeURIComponent(agent)}/prompts/${encodeURIComponent(name)}/diff`, { params: { from, to } })
}

export function savePromptVersion(agent: string, name: string, body: {
  prompt: string | { role: string; content: string }[]
  commitMessage: string
  config?: Record<string, number | string>
}) {
  return post<{ version?: number }>(`/agents/${encodeURIComponent(agent)}/prompts/${encodeURIComponent(name)}/versions`, body)
}

export function promotePrompt(agent: string, name: string, env: 'dev' | 'test' | 'staging', version: number) {
  return post<{ version?: number; gate?: string }>(`/agents/${encodeURIComponent(agent)}/prompts/${encodeURIComponent(name)}/promote`, { env, version })
}

export function rollbackPrompt(agent: string, name: string, version: number, reason: string) {
  return post<unknown>(`/agents/${encodeURIComponent(agent)}/prompts/${encodeURIComponent(name)}/rollback`, { version, reason })
}
