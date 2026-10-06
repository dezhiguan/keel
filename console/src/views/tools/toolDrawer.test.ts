import { describe, expect, it } from 'vitest'
import { breakHint, nextVersion, otherDependents, prodDependents, raisedRisk, replacementId, versionRows, versionTime } from './toolDrawer'

describe('tool drawer', () => {
  it('lists version history newest first', () => {
    const rows = versionRows([
      { version: 'v1', change: '注册', at: '2026-07-30T10:00:00Z' },
      { version: 'v3', change: '当前', at: '2026-09-12T10:00:00Z' },
      { version: 'v2', change: '补充参数', at: '2026-08-02T10:00:00Z' },
      { version: '未知', change: '无时间' },
    ])
    expect(rows.map((row) => row.version)).toEqual(['v3', 'v2', 'v1', '未知'])
    expect(versionTime('2026-10-05T08:00:00+08:00')).toBe('10-05')
    expect(versionTime(null)).toBe('—')
  })

  it('bumps a numeric version and raises risk one step', () => {
    expect(nextVersion('v3')).toBe('v4')
    expect(nextVersion('V9')).toBe('v10')
    expect(nextVersion('初版')).toBe('v2')
    expect(raisedRisk('LOW')).toBe('MID')
    expect(raisedRisk('MID')).toBe('HIGH')
    expect(raisedRisk('HIGH')).toBe('HIGH')
  })

  it('separates prod dependents and strips the new-tool suffix', () => {
    const rows = [
      { agent: 'careermate', env: 'prod' },
      { agent: 'offshore-wind', env: 'staging' },
    ]
    expect(prodDependents(rows).map((row) => row.agent)).toEqual(['careermate'])
    expect(otherDependents(rows).map((row) => row.agent)).toEqual(['offshore-wind'])
    expect(replacementId('echo.note.v2（新建）')).toBe('echo.note.v2')
    expect(breakHint('echo.note')).toBe('不能改原工具：创建 echo.note.v2，原工具进入废弃期')
  })
})
