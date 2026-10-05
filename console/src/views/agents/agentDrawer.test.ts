import { describe, expect, it } from 'vitest'
import { avatarLetter, canRelease, cardChips, cardCost, highlightYaml, readyPill, scoreText, versionRows } from './agentDrawer'

describe('agent drawer', () => {
  it('matches the registry card chips', () => {
    expect(cardChips({ category: 'biz', language: 'java', env: 'prod', version: 'v2.4.1' })).toEqual(['业务', 'Java', 'prod', 'v2.4.1'])
    expect(cardChips({ category: 'dev', language: 'python', runtime: 'code', delegateCount: 2 })).toEqual([
      '研发',
      'Python · 多智能体',
      '编排 2 个',
    ])
    expect(cardChips({ runtime: 'dify' })).toEqual(['Dify'])
    expect(scoreText(0.912)).toBe('0.91')
    expect(scoreText(null)).toBe('—')
    expect(cardCost(31.24)).toBe('¥31.2')
    expect(cardCost(0)).toBe('¥0.0')
    expect(cardCost(null)).toBe('—')
  })

  it('takes the character after the middle dot', () => {
    expect(avatarLetter('小职 · 求职助手')).toBe('求')
    expect(avatarLetter('风机维检')).toBe('风')
  })

  it('shows the registered version when history is empty', () => {
    expect(versionRows({ version: 'v2.4.1', env: 'prod', score: 0.91, gatePassed: true })).toEqual([
      { version: 'v2.4.1', env: 'prod', change: '当前版本', score: '0.91', gate: true, time: '—' },
    ])
  })

  it('marks only the live version as current', () => {
    const rows = versionRows({
      version: 'v2.4.1',
      env: 'prod',
      versions: [
        { version: 'v2.4.1', env: 'prod', gatePassed: true, scoreTotal: 0.91, releasedAt: '2026-09-29T10:20:00+08:00' },
        { version: 'v2.4.0', env: 'prod', gatePassed: true, scoreTotal: 0.93, releasedAt: '2026-09-25T16:02:00+08:00' },
      ],
    })
    expect(rows.map((row) => row.change)).toEqual(['当前版本', '—'])
    expect(rows[0].score).toBe('0.91')
    expect(rows[1].gate).toBe(true)
    expect(rows[0].time).toMatch(/^\d{2}-\d{2} \d{2}:\d{2}$/)
  })

  it('leaves the table empty when nothing has been released', () => {
    expect(versionRows({})).toEqual([])
  })

  it('allows prod release only after the staging gate passes', () => {
    expect(canRelease({ env: 'staging', gatePassed: true, status: 'ONLINE' })).toBe(true)
    expect(canRelease({ env: 'prod', gatePassed: true, status: 'ONLINE' })).toBe(false)
    expect(canRelease({ env: 'staging', gatePassed: false, status: 'ONLINE' })).toBe(false)
    expect(canRelease({ env: 'staging', gatePassed: true, status: 'OFFLINE' })).toBe(false)
  })

  it('escapes yaml before coloring keys', () => {
    expect(highlightYaml('name: <b>x</b> # note')).toBe('<span class="k">name</span>: &lt;b&gt;x&lt;/b&gt; <span class="c"># note</span>')
  })

  it('labels a crashed pod differently from a failed probe', () => {
    expect(readyPill(false, 'k8s').label).toBe('CrashLoop')
    expect(readyPill(true, 'probe')).toEqual({ cls: 'p-ok', label: '200' })
  })
})