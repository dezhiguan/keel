import { describe, expect, it } from 'vitest'
import { isNavActive, NAV } from './nav'

describe('sidebar active item', () => {
  it('keeps 链路追踪 selected on a trace detail route', () => {
    const current = '/traces/565cbabb7c03ffcada738aed5585a94a'
    expect(isNavActive('/traces', current)).toBe(true)
    expect(isNavActive('/overview', current)).toBe(false)
    expect(isNavActive('/eval', current)).toBe(false)
  })

  it('matches the list itself and other nested pages without crossing a path segment', () => {
    expect(isNavActive('/traces', '/traces')).toBe(true)
    expect(isNavActive('/prompts', '/prompts/ops-copilot/system')).toBe(true)
    expect(isNavActive('/agents', '/agents/new')).toBe(true)
    expect(isNavActive('/jobs', '/jobs/DF-0019')).toBe(true)
    expect(isNavActive('/jobs', '/jobs/batches')).toBe(true)
    expect(isNavActive('/eval', '/evaluation')).toBe(false)
    expect(NAV.some((item) => item.path === '/quality')).toBe(false)
    expect(NAV.map((item) => item.title)).toEqual([
      '运行总览', '智能体管理', '智能体研发', '基础服务',
      '链路追踪', '评测中心', '模型网关',
      '工具与技能', '审批中心', '审计中心',
    ])
  })
})
