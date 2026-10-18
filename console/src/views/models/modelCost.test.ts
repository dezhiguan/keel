import { describe, expect, it } from 'vitest'
import {
  allocated,
  allocationPercent,
  allocationTone,
  buildReject,
  channelHost,
  channelShort,
  createModelCostState,
  fixCoverage,
  insertChannel,
  inspectShare,
  missingModels,
  probeChannel,
  prodQwenChannels,
  publishRoute,
  replayDraft,
  replayGapKeys,
  rollbackRoute,
  simulatedKeyIndex,
  switchProdQwen,
  tripChannel,
  uniqueGaps,
} from './modelCost'

describe('model cost proposal', () => {
  it('shows the prototype allocation on the three live channels', () => {
    const state = createModelCostState()
    expect(channelHost(state.channels[0].n)).toBe('dashscope.aliyuncs.com')
    expect(channelHost(state.channels[2].n)).toBe('api.deepseek.com')
    expect(channelShort(state.channels[0].n)).toBe('百炼·生产空间')
    const percents = [0, 1, 2].map((index) => allocationPercent(allocated(state.keys, index), state.channels[index].rpm))
    expect(percents).toEqual([77, 40, 104])
    expect(allocationTone(77).label).toBe('正常')
    expect(allocationTone(104).label).toBe('超分配，需提额')
  })

  it('marks keys that cannot send a model the route might choose', () => {
    const state = createModelCostState()
    expect(missingModels(state.keys[0], state.channels, state.rows)).toEqual(['deepseek-flash'])
    expect(missingModels(state.keys[1], state.channels, state.rows)).toEqual([])
    expect(missingModels(state.keys[2], state.channels, state.rows)).toEqual(['qwen3.8-flash'])
    expect(missingModels(state.keys[4], state.channels, state.rows)).toEqual([])
    expect(uniqueGaps(state.rows, state.keys, state.channels).map((gap) => `${gap.key.a}:${gap.model}`)).toEqual([
      'careermate-prod:deepseek-flash',
      'askdb-prod:qwen3.8-flash',
    ])
  })

  it('blocks a share that exceeds the vendor cap and explains coverage', () => {
    const state = createModelCostState()
    const key = state.keys[0]
    const report = inspectShare(key, { 0: 400, 2: 50 }, state.keys, state.channels, state.rows)
    expect(report.over).toBe(true)
    expect(report.lines[1]).toMatchObject({ name: 'DeepSeek·官方', total: 570, limit: 500, over: true })
    expect(report.missing).toEqual([])
    expect(report.uses).toContain('long-context（deepseek-flash）')
    const open = inspectShare(key, { 0: 400 }, state.keys, state.channels, state.rows)
    expect(open.over).toBe(false)
    expect(open.missing).toEqual(['deepseek-flash'])
    expect(inspectShare(key, {}, state.keys, state.channels, state.rows).empty).toBe(true)
  })

  it('adds fifty RPM only where the channel still has room', () => {
    const state = createModelCostState()
    const result = fixCoverage(state.keys, state.channels, state.rows)
    expect(result.added).toBe(1)
    expect(result.fails).toEqual(['careermate-prod（prod 没有 deepseek-flash 渠道或已满）'])
    expect(state.keys[2].sh[0]).toBe(50)
    expect(allocated(state.keys, 0)).toBe(970)
  })

  it('keeps existing share indexes when a channel is inserted before batch', () => {
    const state = createModelCostState()
    insertChannel(state.channels, { n: '百炼·主账号B·生产空间', m: 'qwen3.8-flash', env: 'dev/test/staging', rpm: 1200 })
    expect(state.channels.map((channel) => channel.env)).toEqual(['prod', 'dev', 'prod', 'dev', '非实时'])
    expect(state.keys[0].sh[0]).toBe(400)
    expect(state.channels.at(-1)?.batch).toBe(true)
  })

  it('moves prod qwen shares onto the selected channel', () => {
    const state = createModelCostState()
    insertChannel(state.channels, { n: '百炼·主账号B·生产空间', m: 'qwen3.8-flash', env: 'prod', rpm: 1200 })
    const target = prodQwenChannels(state.channels).at(-1)
    expect(target?.channel.n).toBe('百炼·主账号B·生产空间')
    expect(switchProdQwen(state.keys, state.channels, target!.index)).toBe(3)
    expect(state.keys[0].sh).toEqual({ 3: 400 })
    expect(state.keys[1].sh).toEqual({ 3: 420, 2: 100 })
    expect(state.keys[3].sh).toEqual({ 2: 120, 3: 100 })
  })

  it('separates gateway 429 from a vendor failover', () => {
    const state = createModelCostState()
    const now = 1_000
    expect(buildReject(state.keys, state.channels, 0, false, now).label).toBe('网关拒绝 429')
    expect(buildReject(state.keys, state.channels, 0, true, now).reason).toContain('没有可用渠道')
    insertChannel(state.channels, { n: '百炼·主账号B·生产空间', m: 'qwen3.8-flash', env: 'prod', rpm: 1200 })
    const blocked = buildReject(state.keys, state.channels, 0, true, now)
    expect(blocked.reason).toContain('没有允许这个渠道')
    state.keys[0].sh[3] = 50
    expect(buildReject(state.keys, state.channels, 0, true, now).label).toBe('厂商 429 → 已换渠道')
    expect(simulatedKeyIndex(0.99, state.keys.length)).toBe(3)
  })

  it('opens the breaker for ten seconds and then half-opens', () => {
    const state = createModelCostState()
    const channel = state.channels[0]
    tripChannel(channel, 5_000)
    expect(channel.br?.until).toBe(15_000)
    probeChannel(channel)
    expect(channel.br).toBeNull()
  })

  it('replays the published 80% baseline and refuses publish while keys are uncovered', () => {
    const state = createModelCostState()
    const replay = replayDraft(80, replayGapKeys(state.rows[0], state.keys, state.channels))
    expect(replay).toMatchObject({ qwen: 80, deepseek: 20, cost: '41.20', delta: '0.0', deltaUp: false, errors: 37, lost: 749 })
    expect(publishRoute(state)).toEqual({ ok: false, keys: 2 })
    expect(rollbackRoute(state)).toBe(false)
  })

  it('publishes once coverage is complete and rolls back to the previous rows', () => {
    const state = createModelCostState()
    state.rows.forEach((row) => { row.w = 100 })
    fixCoverage(state.keys, state.channels, state.rows)
    expect(publishRoute(state)).toEqual({ ok: true, version: 8 })
    state.rows[0].w = 10
    expect(rollbackRoute(state)).toBe(true)
    expect(state.ver).toBe(7)
    expect(state.rows[0].w).toBe(100)
  })
})
