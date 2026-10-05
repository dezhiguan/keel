import { describe, expect, it } from 'vitest'
import { avatarLetter, canRelease, highlightYaml, readyPill, versionRows } from './agentDrawer'

describe('agent drawer', () => {
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