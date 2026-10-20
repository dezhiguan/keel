import { describe, expect, it } from 'vitest'
import { grantPayload } from '@/api/skills'
import { cleanCases, cleanExamples, formatSkillWhen, lineDiff, pageSlice, sameSkillContent, skillNameError, sourceLabel, splitList, versionMeta } from './skillView'

describe('skill grant', () => {
  it('sends only the skill name and version', () => {
    expect(grantPayload('cite-from-kb', 3)).toEqual({ name: 'cite-from-kb', version: 3 })
    expect(grantPayload('cite-from-kb', 3)).not.toHaveProperty('tools')
  })

  it('accepts a prototype skill name and explains an invalid one', () => {
    expect(skillNameError(' Cite-From-KB ')).toBe('')
    expect(skillNameError('cite-from-kb')).toBe('')
    expect(skillNameError('引用知识库')).toBe('技能名只能是小写字母、数字和连字符，例如 cite-from-kb')
    expect(skillNameError('')).toBe('技能名不能为空')
  })

  it('splits tool names and labels the source', () => {
    expect(splitList('rag.search, sql.execute\nrag.search')).toEqual(['rag.search', 'sql.execute'])
    expect(sourceLabel('EXPERIENCE')).toBe('经验沉淀')
    expect(sourceLabel('AUTHORED')).toBe('人工编写')
  })

  it('keeps several examples and cases, and ignores blank rows', () => {
    expect(cleanExamples([' 引用出处 ', '', '引用出处'])).toEqual(['引用出处'])
    expect(cleanCases([
      { id: 'case-1', input: ' 没有资料 ', expectedOutput: '无法确认' },
      { input: '   ', expectedOutput: '丢掉' },
    ])).toEqual([{ id: 'case-1', input: '没有资料', expectedOutput: '无法确认' }])
  })

  it('pages the registry the same way as the tools pager', () => {
    const items = Array.from({ length: 12 }, (_, index) => `s${index + 1}`)
    expect(pageSlice(items, 1, 10)).toEqual(items.slice(0, 10))
    expect(pageSlice(items, 2, 10)).toEqual(['s11', 's12'])
    expect(pageSlice(items, 3, 10)).toEqual([])
  })

  it('diffs a version against the previous one and refuses an unchanged body', () => {
    expect(lineDiff('写出文档名。\n不要编造。', '写出文档名和段落号。\n不要编造。')).toEqual([
      { kind: 'del', text: '写出文档名。' },
      { kind: 'add', text: '写出文档名和段落号。' },
      { kind: 'same', text: '不要编造。' },
    ])
    expect(sameSkillContent(
      { instructions: '引用', tools: ['rag.search'], examples: ['甲'], cases: [{ input: '问', expectedOutput: '答' }] },
      { instructions: '引用 ', tools: ['rag.search'], examples: ['甲'], cases: [{ input: '问', expectedOutput: '答' }] },
    )).toBe(true)
    expect(formatSkillWhen('2026-10-18T14:02:00+08:00')).toBe('10-18 14:02')
    expect(versionMeta({ createdBy: '官德志', createdAt: '2026-10-18T14:02:00+08:00', note: '引用必须带段落号' }))
      .toBe('官德志 · 10-18 14:02 · 引用必须带段落号')
    expect(versionMeta({ createdAt: '2026-10-18T14:02:00+08:00' })).toBe('10-18 14:02')
  })
})