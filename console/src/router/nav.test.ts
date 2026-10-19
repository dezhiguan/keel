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
      '运行总览', '智能体目录', '智能体生产线', '平台与能力服务',
      '调用链路', '评测与门禁', '提示词工程',
      '工具与技能', '待我处理', '审计留痕', '模型与成本',
    ])
  })
})
