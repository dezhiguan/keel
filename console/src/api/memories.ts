import { del, get } from './http'

export interface UserMemory {
  id: number
  ownerUser: string
  layer?: 'USER' | 'TASK' | 'EXPERIENCE'
  content: string
  createdAt?: string
}

export function listMemories(agent: string, ownerUser?: string) {
  return get<{ items?: UserMemory[] }>(`/agents/${encodeURIComponent(agent)}/memories`, {
    params: { ownerUser: ownerUser || undefined },
  })
}

export function deleteMemory(agent: string, id: number) {
  return del<{ id?: number }>(`/agents/${encodeURIComponent(agent)}/memories/${id}`)
}
