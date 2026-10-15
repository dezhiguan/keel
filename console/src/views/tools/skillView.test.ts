import { describe, expect, it } from 'vitest'
import { grantPayload } from '@/api/skills'
import { sourceLabel, splitList } from './skillView'

describe('skill grant', () => {
  it('sends only the skill name and version', () => {
    expect(grantPayload('cite-from-kb', 3)).toEqual({ name: 'cite-from-kb', version: 3 })
    expect(grantPayload('cite-from-kb', 3)).not.toHaveProperty('tools')
  })

  it('splits tool names and labels the source', () => {
    expect(splitList('rag.search, sql.execute\nrag.search')).toEqual(['rag.search', 'sql.execute'])
    expect(sourceLabel('EXPERIENCE')).toBe('经验沉淀')
    expect(sourceLabel('AUTHORED')).toBe('人工编写')
  })
})