import { del, get, post } from './http'

export interface SkillSummary {
  name: string
  version: number
  ownerOrg?: string
  description?: string
  source?: 'AUTHORED' | 'EXPERIENCE'
  tools?: string[]
  risk?: 'LOW' | 'MID' | 'HIGH'
  users?: string[]
}

export interface SkillGrant {
  name: string
  version: number
  description?: string
  tools?: string[]
  risk?: 'LOW' | 'MID' | 'HIGH'
  status?: 'GRANTED'
}

export interface SkillCase {
  id?: string
  input: string
  expectedOutput?: string
}

export interface SkillVersion {
  version: number
  instructions: string
  note?: string
  createdBy?: string
  createdAt?: string
  tools?: string[]
  examples?: string[]
  cases?: SkillCase[]
}

export interface SkillUser {
  agent: string
  version: number
  status?: 'LATEST' | 'BEHIND' | 'RUNNING'
  score?: number
  threshold?: number
  upgradeError?: string
}

export interface SkillDetail {
  name: string
  ownerOrg?: string
  description?: string
  source?: 'AUTHORED' | 'EXPERIENCE'
  latest: number
  versions?: SkillVersion[]
  users?: SkillUser[]
}

export function listSkills() {
  return get<{ items?: SkillSummary[] }>('/skills')
}

export function publishSkill(body: {
  name: string
  ownerOrg: string
  description: string
  source?: 'AUTHORED' | 'EXPERIENCE'
  instructions: string
  tools?: string[]
  examples?: string[]
  cases?: SkillCase[]
  note?: string
}) {
  return post<{ name?: string; version?: number }>('/skills', body)
}

export function getSkill(name: string) {
  return get<SkillDetail>(`/skills/${encodeURIComponent(name)}`)
}

export function revokeSkill(agent: string, name: string) {
  return del<{ agent?: string; skill?: string }>(`/agents/${encodeURIComponent(agent)}/skills/${encodeURIComponent(name)}`)
}

export function upgradeSkill(agent: string, name: string) {
  return post<{ agent?: string; skill?: string; version?: number; runId?: string; status?: string }>(
    `/agents/${encodeURIComponent(agent)}/skills/${encodeURIComponent(name)}/upgrade`,
  )
}

export function listAgentSkills(agent: string) {
  return get<{ items?: SkillGrant[] }>(`/agents/${encodeURIComponent(agent)}/skills`)
}

export function grantSkill(agent: string, name: string, version: number) {
  return post<{ name?: string; version?: number; toolsGranted?: string[]; casesMerged?: number }>(
    `/agents/${encodeURIComponent(agent)}/skills`,
    grantPayload(name, version),
  )
}

export function grantPayload(name: string, version: number) {
  return { name, version }
}
