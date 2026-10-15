import { describe, expect, it } from 'vitest'
import { avatarLetter, canRelease, cardChips, cardCost, channelIdentity, channelMethod, elementCell, executionRows, highlightYaml, isSelfChecking, kindLine, memoryRows, memoryTone, readyPill, resourceStatus, scoreText, toolChip, versionRows, withEvalGate } from './agentDrawer'

describe('agent drawer', () => {
  it('shows memory status from the API and keeps unavailable layers explicit', () => {
    expect(memoryRows({ session: '本地开发可用 · 7 天', user: '未接入' })).toEqual([
      ['会话记忆', '本地开发可用 · 7 天'], ['用户记忆', '未接入'],
      ['任务记忆', '未接入'], ['经验记忆', '未接入'],
    ])
  })
  it('renders execution data returned by the agent detail API', () => {
    expect(executionRows({ current: '声明为智能体', target: 'ctx.loop', constraints: 'maxSteps 4' })).toEqual([
      ['现在', '声明为智能体'], ['目标', 'ctx.loop'], ['约束', 'maxSteps 4'],
    ])
    expect(executionRows(null)[0]).toEqual(['现在', '未声明'])
  })
  it('lays out element cells the way the overview grid does', () => {
    expect(elementCell({ name: '模型接入', passed: true, detail: 'manifest 已声明' }, 4)).toMatchObject({
      label: '5. 模型接入', cls: 'ok', mark: '✓', note: '',
    })
    expect(elementCell({ name: '记忆', passed: false, detail: 'manifest 缺少核心声明' }, 2).cls).toBe('no core')
    expect(elementCell({ name: '评测', passed: null, detail: 'manifest 已声明；运行行为待门禁验证' }, 6)).toMatchObject({
      cls: 'mid core', mark: '·', note: '待验证',
    })
    expect(elementCell({ name: '知识检索', passed: null, detail: '未声明（可选）' }, 5).note).toBe('未声明')
    expect(elementCell({ name: '人在回路', passed: null, detail: '无高风险工具' }, 8).note).toBe('—')
    expect(elementCell({ name: '人在回路', passed: null, detail: '高风险工具已声明审批；挂起恢复待实测' }, 8).note).toBe('有，未实测')
  })
  it('colors memory status and names channel columns', () => {
    expect(memoryTone('未接入')).toBe('p-mute')
    expect(memoryTone('候选 3 条待人审')).toBe('p-warn')
    expect(memoryTone('可查可删')).toBe('p-ok')
    expect(channelMethod('HOSTED')).toBe('独立成站')
    expect(channelMethod('EMBED')).toBe('对话气泡')
    expect(channelMethod('MINIPROGRAM')).toBe('小程序 SDK')
    expect(channelIdentity('HOSTED', 'EXCHANGE')).toBe('登录用户（换票）')
    expect(channelIdentity('EMBED', 'EXCHANGE')).toBe('业务系统用户（换票）')
    expect(channelIdentity('MINIPROGRAM', 'OPENID')).toBe('openid 映射')
  })
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
    expect(cardCost(0.00342)).toBe('¥0.0034')
    expect(cardCost(null)).toBe('—')
  })

  it('labels the meta-agent type on its own', () => {
    expect(kindLine({ name: 'meta-agent', language: 'Python' })).toBe('元智能体 · Python')
    expect(kindLine({ category: 'biz', language: 'Java' })).toBe('业务智能体 · Java')
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

  it('labels provisioned resources the way the drawer shows them', () => {
    expect(resourceStatus('litellm_key', 'ACTIVE')).toEqual({ cls: 'p-ok', label: '有效' })
    expect(resourceStatus('secret', 'ACTIVE')).toEqual({ cls: 'p-ok', label: '已写入' })
    expect(resourceStatus('dataset', 'ACTIVE')).toEqual({ cls: 'p-ok', label: '已导入' })
    expect(resourceStatus('oauth_client', 'ACTIVE')).toEqual({ cls: 'p-ok', label: '已注册' })
    expect(resourceStatus('litellm_key', 'REVOKED').label).toBe('已吊销')
    expect(resourceStatus('secret', 'REVOKED').label).toBe('已删除')
    expect(toolChip('ticket.create · 写 · 高 · 需审批')).toEqual({ lead: 'ticket.create · 写', high: true })
    const gated = withEvalGate({
      score: 0.9,
      selfCheck: { items: [{ name: '评测门禁', passed: null, detail: '未运行' }] },
    })
    expect(gated.selfCheck?.items?.[0]).toMatchObject({ passed: true, detail: '0.90 ≥ 0.85' })
    const kept = withEvalGate({
      score: 0.5,
      selfCheck: { items: [{ name: '评测门禁', passed: true, detail: '0.90 ≥ 0.80' }] },
    })
    expect(kept.selfCheck?.items?.[0]?.detail).toBe('0.90 ≥ 0.80')
  })

  it('shows 自检中 only on the agent whose probe is running', () => {
    expect(isSelfChecking(['qa-prompt-1013'], 'qa-prompt-1013')).toBe(true)
    expect(isSelfChecking(['qa-prompt-1013'], 'chat-demo')).toBe(false)
    expect(isSelfChecking([], 'chat-demo')).toBe(false)
  })

  it('labels a crashed pod differently from a failed probe', () => {
    expect(readyPill(false, 'k8s').label).toBe('CrashLoop')
    expect(readyPill(true, 'probe')).toEqual({ cls: 'p-ok', label: '200' })
  })
})
