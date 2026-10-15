import { get, post } from './http'

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
}) {
  return post<{ name?: string; version?: number }>('/skills', body)
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
