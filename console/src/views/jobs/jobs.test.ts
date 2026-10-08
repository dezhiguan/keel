import { describe, expect, it } from 'vitest'
import type { DevflowJob } from '@/api/devflow'
import {
  applyAssist,
  applyHandback,
  applyTakeover,
  artifacts,
  canCancel,
  canTakeover,
  passedGate,
  repoLabel,
  statusView,
  stripMarks,
  timeline,
  toggleTemplate,
  waitingCount,
} from './jobs'

function job(partial: Partial<DevflowJob> & Pick<DevflowJob, 'jobId' | 'status' | 'stage'>): DevflowJob {
  return {
    title: '样例',
    layer: 'BIZ',
    kind: 'CREATE',
    mode: 'AUTO',
    targetAgent: 'sample',
    producerAgent: 'dev-lead',
    template: 'tool-agent',
    spentCny: 1,
    budgetCny: 80,
    fixRounds: 0,
    maxFixRounds: 3,
    needReview: false,
    requester: 'amy',
    ownerOrg: '研发效能组',
    goal: '目标',
    tools: [],
    knowledge: [],
    seed: { human: 0, agent: 0, holdout: 0 },
    events: [],
    ...partial,
  }
}

describe('devflow job rules', () => {
  it('labels review, watch, and human-dev the way the board does', () => {
    expect(statusView(job({ jobId: 'DF-0021', status: 'WAIT', stage: 'REVIEW', needReview: true })).label).toBe('等人工 review PR')
    expect(statusView(job({ jobId: 'DF-0014', status: 'RUN', stage: 'WATCH', watchDay: 3 })).label).toBe('观察中 · 第 3/7 天')
    expect(statusView(job({ jobId: 'DF-0015', status: 'HUMAN', stage: 'BUILD', humanDevUser: '王工' })).label).toBe('人工开发 · 王工')
    expect(statusView(job({ jobId: 'DF-0019', status: 'RUN', stage: 'GATE', layer: 'BIZ' })).label).toBe('release-agent 执行中')
    expect(statusView(job({ jobId: 'DF-0021', status: 'RUN', stage: 'BUILD', layer: 'DEV' })).label).toContain('meta-agent')
  })

  it('allows takeover only while building, failed, or waiting on a PR', () => {
    expect(canTakeover(job({ jobId: 'a', status: 'RUN', stage: 'GATE' }))).toBe(true)
    expect(canTakeover(job({ jobId: 'a', status: 'WAIT', stage: 'REVIEW', needReview: true }))).toBe(true)
    expect(canTakeover(job({ jobId: 'a', status: 'FAIL', stage: 'GATE' }))).toBe(true)
    expect(canTakeover(job({ jobId: 'a', status: 'HUMAN', stage: 'BUILD' }))).toBe(false)
    expect(canTakeover(job({ jobId: 'a', status: 'DONE', stage: 'WATCH' }))).toBe(false)
    expect(canTakeover(job({ jobId: 'a', status: 'WAIT', stage: 'H1' }))).toBe(false)
  })

  it('refuses to cancel a job that is already in production watch', () => {
    expect(canCancel(job({ jobId: 'a', status: 'RUN', stage: 'WATCH' }))).toBe(false)
    expect(canCancel(job({ jobId: 'a', status: 'QUEUED', stage: 'SPEC' }))).toBe(true)
    expect(canCancel(job({ jobId: 'a', status: 'DONE', stage: 'WATCH' }))).toBe(false)
  })

  it('moves a review back to build when a person takes over', () => {
    const result = applyTakeover(job({ jobId: 'DF-0021', status: 'WAIT', stage: 'REVIEW', needReview: true }), '官德志', '12:00')
    expect('job' in result && result.job).toMatchObject({ status: 'HUMAN', stage: 'BUILD', needReview: false, humanDevUser: '官德志' })
  })

  it('stops a failed release at the gate when taken over', () => {
    const result = applyTakeover(job({ jobId: 'a', status: 'FAIL', stage: 'RELEASE' }), '官德志', '12:00')
    expect('job' in result && result.job.stage).toBe('GATE')
  })

  it('rejects takeover once the job is done', () => {
    const result = applyTakeover(job({ jobId: 'DF-0013', status: 'DONE', stage: 'WATCH' }), '官德志', '12:00')
    expect(result).toEqual({ error: '当前阶段不能人工接管' })
  })

  it('hands a scaffold job back as a gate submission', () => {
    const current = job({ jobId: 'DF-0015', status: 'HUMAN', stage: 'BUILD', mode: 'SCAFFOLD', humanDevUser: '王工' })
    const empty = applyAssist(current, '  ', '官德志', '12:01')
    expect(empty).toEqual({ error: '请写明要它做什么' })
    const helped = applyAssist(current, '补 tools/ 的单测', '官德志', '12:01')
    expect('job' in helped && helped.job.events.at(-1)?.summary).toBe('按指令完成，推送 1 次提交')
    const back = applyHandback(current, '官德志', '12:02')
    expect('job' in back && back.job).toMatchObject({ status: 'RUN', stage: 'REVIEW' })
    expect('job' in back && back.job.events.at(-1)?.summary).toBe('提交门禁')
  })

  it('keeps the last template selected', () => {
    expect(toggleTemplate(['tool-agent'], 'tool-agent')).toEqual(['tool-agent'])
    expect(toggleTemplate(['tool-agent'], 'chat-rag')).toEqual(['tool-agent', 'chat-rag'])
  })

  it('marks the current stage on the strip and counts people waiting', () => {
    const marks = stripMarks(job({ jobId: 'a', status: 'WAIT', stage: 'H1' }))
    expect(marks[0]?.cls).toBe('done')
    expect(marks[1]?.cls).toContain('wait')
    expect(waitingCount([
      job({ jobId: '1', status: 'WAIT', stage: 'H1' }),
      job({ jobId: '2', status: 'WAIT', stage: 'REVIEW', needReview: true }),
      job({ jobId: '3', status: 'RUN', stage: 'BUILD' }),
    ])).toBe(2)
  })

  it('derives artifacts and the repo from the stage', () => {
    const early = job({ jobId: 'a', status: 'WAIT', stage: 'H1' })
    expect(artifacts(early).every((item) => !item.ready)).toBe(true)
    expect(repoLabel(early)).toBe('未创建')
    const building = job({ jobId: 'b', status: 'HUMAN', stage: 'BUILD', mode: 'SCAFFOLD' })
    expect(artifacts(building).find((item) => item.kind === 'CODE')).toMatchObject({ ready: true, origin: 'AGENT + HUMAN' })
    expect(repoLabel(building)).toBe('keel-agents/sample')
    const done = job({ jobId: 'c', status: 'DONE', stage: 'WATCH' })
    expect(artifacts(done).every((item) => item.ready)).toBe(true)
    expect(passedGate(done)).toBe(true)
    expect(timeline(job({ jobId: 'd', status: 'RUN', stage: 'GATE', fixRounds: 1 })).find((row) => row.key === 'GATE')?.detail).toContain('第 2 轮')
  })
})