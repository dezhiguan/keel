import type { DevflowJob } from '@/api/devflow'

export const BOARD_POLL_MS = 30_000
export const DETAIL_POLL_MS = 10_000

export const STAGES = [
  { key: 'SPEC', name: '需求分析', actor: 'spec-agent', human: false, who: '' },
  { key: 'H1', name: '需求确认', actor: '', human: true, who: '需求提出人' },
  { key: 'EVAL', name: '评测准备', actor: 'eval-agent', human: false, who: '' },
  { key: 'H2', name: '评测确认', actor: '', human: true, who: '业务负责人' },
  { key: 'H3', name: '授权审批', actor: '', human: true, who: '工具 / 知识库所有者' },
  { key: 'BUILD', name: '开发', actor: 'dev-agent', human: false, who: '' },
  { key: 'REVIEW', name: '代码评审', actor: 'code-review', human: false, who: '' },
  { key: 'GATE', name: 'staging 门禁', actor: 'release-agent', human: false, who: '' },
  { key: 'H4', name: '发布审批', actor: '', human: true, who: '智能体负责人' },
  { key: 'RELEASE', name: '发布', actor: 'release-agent', human: false, who: '' },
  { key: 'WATCH', name: '上线观察', actor: 'release-agent', human: false, who: '' },
] as const

export type StageKey = (typeof STAGES)[number]['key']

export const COLUMNS: { name: string; hint: string; stages: StageKey[] }[] = [
  { name: '需求', hint: 'S1 · H1', stages: ['SPEC', 'H1'] },
  { name: '评测与授权', hint: 'S2 · H2 · H3', stages: ['EVAL', 'H2', 'H3'] },
  { name: '开发与评审', hint: 'S3 · S4', stages: ['BUILD', 'REVIEW'] },
  { name: '门禁', hint: 'S5', stages: ['GATE'] },
  { name: '发布与观察', hint: 'H4 · S6 · S7', stages: ['H4', 'RELEASE', 'WATCH'] },
]

export const MODES = {
  AUTO: { label: '全托管' },
  COLLAB: { label: '人机协作' },
  SCAFFOLD: { label: '只生成骨架' },
} as const

export const TEMPLATES = ['tool-agent', 'chat-rag', 'graph-agent', 'supervisor', 'java-spring']

const STAGE_INDEX = Object.fromEntries(STAGES.map((stage, index) => [stage.key, index])) as Record<StageKey, number>

const STAGE_MARK: Partial<Record<DevflowJob['status'], string>> = {
  FAIL: 'fail',
  WAIT: 'wait',
  HUMAN: 'hum',
  RUN: 'cur',
}

const STAGE_RESULT: Partial<Record<DevflowJob['status'], string>> = {
  FAIL: '失败',
  WAIT: '等待人工',
  HUMAN: '人工开发',
  RUN: '进行中',
  CANCEL: '已取消',
  QUEUED: '排队',
}

const ACTOR_COLOR: Record<string, string> = {
  'meta-agent': '#ff7a45',
  'dev-lead': '#f1b44c',
  'spec-agent': '#e8c26a',
  'dev-agent': '#5b9cf6',
  'eval-agent': '#34c38f',
  'code-review': '#e36fae',
  'ci-doctor': '#f46a6a',
  'release-agent': '#b48cf2',
}

export type Transition = { job: DevflowJob } | { error: string }

export function stageIndex(stage: StageKey): number {
  return STAGE_INDEX[stage]
}

export function isActive(status: DevflowJob['status']): boolean {
  return status === 'RUN' || status === 'WAIT' || status === 'HUMAN'
}

export function isFinished(status: DevflowJob['status']): boolean {
  return status === 'DONE' || status === 'FAIL' || status === 'CANCEL'
}

export function isLive(status: DevflowJob['status']): boolean {
  return isActive(status) || status === 'QUEUED'
}

export function layerShort(layer: DevflowJob['layer']): string {
  if (layer === 'META') return '元'
  if (layer === 'DEV') return '研发'
  return '业务'
}

export function layerClass(layer: DevflowJob['layer']): string {
  return layer.toLowerCase()
}

export function kindLabel(kind: DevflowJob['kind']): string {
  return kind === 'CREATE' ? '新建' : '改造'
}

export function handbackLabel(mode: DevflowJob['mode']): string {
  return mode === 'SCAFFOLD' ? '提交门禁' : '交还智能体'
}

export function cardTone(status: DevflowJob['status']): string {
  if (status === 'WAIT') return 'need'
  if (status === 'HUMAN') return 'hum'
  return ''
}

export function actorColor(name: string): string {
  return ACTOR_COLOR[name] ?? 'var(--text)'
}

export function cny(value: number, digits = 1): string {
  return `¥${value.toFixed(digits)}`
}

export function budgetWidth(spent: number, budget: number): number {
  if (budget <= 0) return 0
  return Math.min(100, (spent / budget) * 100)
}

export function budgetHot(spent: number, budget: number): boolean {
  return budget > 0 && spent / budget > 0.8
}

export function seedTotal(seed: DevflowJob['seed']): number {
  return seed.human + seed.agent
}

export function visibleSeed(seed: DevflowJob['seed']): number {
  return Math.max(0, seed.human - seed.holdout)
}

export function passedGate(job: Pick<DevflowJob, 'status' | 'stage'>): boolean {
  return job.status === 'DONE' || stageIndex(job.stage) >= stageIndex('H4')
}

export function waitingCount(jobs: Pick<DevflowJob, 'status' | 'stage' | 'needReview'>[]): number {
  return jobs.filter((job) => job.status === 'WAIT' && (job.needReview || job.stage === 'H1' || job.stage === 'H2' || job.stage === 'H4')).length
}

export function canTakeover(job: Pick<DevflowJob, 'status' | 'stage' | 'needReview'>): boolean {
  const building = job.status === 'RUN' && (job.stage === 'BUILD' || job.stage === 'REVIEW' || job.stage === 'GATE')
  return building || job.status === 'FAIL' || (job.needReview && job.status === 'WAIT')
}

export function canHandback(job: Pick<DevflowJob, 'status'>): boolean {
  return job.status === 'HUMAN'
}

export function canAssist(job: Pick<DevflowJob, 'status'>): boolean {
  return job.status === 'HUMAN'
}

export function canCancel(job: Pick<DevflowJob, 'status' | 'stage'>): boolean {
  const open = job.status === 'RUN' || job.status === 'WAIT' || job.status === 'HUMAN' || job.status === 'QUEUED'
  return open && job.stage !== 'WATCH'
}

export function showApprovalLink(job: Pick<DevflowJob, 'status' | 'stage' | 'needReview'>): boolean {
  return job.status === 'WAIT' && (job.needReview || job.stage === 'H1' || job.stage === 'H2' || job.stage === 'H4')
}

export function statusView(job: Pick<DevflowJob, 'status' | 'stage' | 'layer' | 'needReview' | 'humanDevUser' | 'watchDay'>): { label: string; cls: string } {
  if (job.status === 'FAIL') return { label: '失败', cls: 'p-bad' }
  if (job.status === 'CANCEL') return { label: '已取消', cls: 'p-mute' }
  if (job.status === 'DONE') return { label: '已完成', cls: 'p-ok' }
  if (job.status === 'QUEUED') return { label: '批次排队', cls: 'p-mute' }
  if (job.status === 'HUMAN') return { label: `人工开发 · ${job.humanDevUser || '—'}`, cls: 'p-vio' }
  if (job.status === 'WAIT') {
    if (job.needReview) return { label: '等人工 review PR', cls: 'p-warn' }
    const stage = STAGES.find((item) => item.key === job.stage)
    return { label: `等${stage?.who ?? ''}`, cls: 'p-warn' }
  }
  if (job.stage === 'WATCH') return { label: `观察中 · 第 ${job.watchDay || 1}/7 天`, cls: 'p-teal' }
  const stage = STAGES.find((item) => item.key === job.stage)
  const actor = job.layer === 'DEV' ? 'meta-agent' : stage?.actor
  return { label: `${actor} 执行中`, cls: 'p-acc' }
}

export function stripMarks(job: Pick<DevflowJob, 'status' | 'stage'>): { cls: string; title: string }[] {
  const current = stageIndex(job.stage)
  return STAGES.map((stage, index) => {
    let cls = ''
    if (job.status === 'DONE' || index < current) cls = 'done'
    else if (index === current) cls = STAGE_MARK[job.status] ?? ''
    if (stage.human) cls = `${cls} h`.trim()
    return { cls, title: stage.name }
  })
}

export interface TimelineRow {
  key: StageKey
  title: string
  detail: string
  result: string
  mark: string
  human: boolean
  glyph: string
}

export function timeline(job: DevflowJob): TimelineRow[] {
  const current = stageIndex(job.stage)
  return STAGES.map((stage, index) => {
    let mark = ''
    let result = ''
    if (job.status === 'DONE' || index < current) {
      mark = 'done'
      result = '完成'
    } else if (index === current) {
      mark = STAGE_MARK[job.status] ?? ''
      result = STAGE_RESULT[job.status] ?? ''
    }
    const actor = stage.human
      ? `人工关口 · ${job.layer === 'DEV' ? '平台管理员' : stage.who}`
      : (job.layer === 'DEV' ? 'meta-agent' : stage.actor)
    let detail = actor
    if (stage.key === 'BUILD') detail += ` · ${MODES[job.mode].label}`
    if (stage.key === 'GATE' && job.fixRounds) detail += ` · 第 ${job.fixRounds + 1} 轮`
    const glyph = mark === 'done' ? '✓' : mark === 'fail' ? '✕' : (mark === 'hum' || stage.human) ? '人' : String(index + 1)
    return { key: stage.key, title: `${stage.key} · ${stage.name}`, detail, result, mark, human: stage.human, glyph }
  })
}

export interface ArtifactRow {
  kind: string
  label: string
  origin: string
  ready: boolean
}

const ARTIFACTS: { kind: string; label: string; origin: string; after: StageKey }[] = [
  { kind: 'SPEC', label: '需求单', origin: 'HUMAN 确认', after: 'H1' },
  { kind: 'MANIFEST', label: 'agent.yaml', origin: 'AGENT', after: 'H1' },
  { kind: 'EVALSET', label: 'evals/seed.jsonl', origin: 'HUMAN + AGENT', after: 'H2' },
  { kind: 'CODE', label: 'PR 提交', origin: '', after: 'REVIEW' },
  { kind: 'REVIEW', label: '代码评审结论', origin: 'AGENT', after: 'GATE' },
  { kind: 'GATE_REPORT', label: '门禁报告', origin: 'AGENT', after: 'H4' },
]

export function artifacts(job: DevflowJob): ArtifactRow[] {
  return ARTIFACTS.map((item) => {
    const origin = item.kind === 'CODE'
      ? `AGENT${job.status === 'HUMAN' || job.mode !== 'AUTO' ? ' + HUMAN' : ''}`
      : item.origin
    const ready = job.status === 'DONE' || stageIndex(job.stage) > stageIndex(item.after) || (item.kind === 'CODE' && job.status === 'HUMAN')
    return { kind: item.kind, label: item.label, origin, ready }
  })
}

export function repoLabel(job: Pick<DevflowJob, 'stage' | 'targetAgent'>): string {
  return stageIndex(job.stage) >= stageIndex('BUILD') ? `keel-agents/${job.targetAgent}` : '未创建'
}

export interface DetailNotice {
  tone: 'vio' | 'warn' | 'info'
  title?: string
  text: string
}

export function detailNotices(job: DevflowJob, concurrency = 3): DetailNotice[] {
  const notes: DetailNotice[] = []
  if (job.status === 'HUMAN') {
    notes.push({
      tone: 'vio',
      title: '人工开发中',
      text: `开发者 ${job.humanDevUser || '—'} 在 keel-agents/${job.targetAgent} 的 PR 上提交。dev-agent 已停止推送，修复轮次暂停计数。完成后点「${handbackLabel(job.mode)}」。`,
    })
  }
  if (job.status === 'WAIT' && job.needReview) {
    notes.push({ tone: 'warn', text: '人机协作模式：PR 需要至少 1 名人工 approve 才进门禁（分支保护强制）。' })
  } else if (job.status === 'WAIT') {
    const stage = STAGES.find((item) => item.key === job.stage)
    const kind = stage?.key === 'H4' ? 'tool.call · git.pr.merge' : 'input_required'
    notes.push({ tone: 'warn', text: `停在 ${stage?.key} ${stage?.name}，等 ${stage?.who}。待办在审批中心（${kind}）。` })
  }
  if (job.status === 'QUEUED' && job.batchId) {
    notes.push({ tone: 'info', text: `批次 ${job.batchId} 排队中：试产条目过门禁后按并发上限 ${concurrency} 出队。` })
  }
  return notes
}

export function manifestYaml(job: DevflowJob): string {
  const tools = job.tools.length
    ? job.tools.map((tool) => {
      const access = /send|create/.test(tool.name) ? 'write' : 'read'
      return `    - { name: ${tool.name}, access: ${access}, risk: ${tool.risk.toLowerCase()} }`
    }).join('\n')
    : '    []'
  const knowledge = job.knowledge.map((name) => ` { kb: ${name} }`).join(',')
  return `apiVersion: keel/v1
kind: Agent
metadata: { name: ${job.targetAgent}, displayName: ${job.title}, owner: ${job.ownerOrg} / ${job.requester} }
spec:
  runtime:  { type: code, language: python, liveness: k8s }
  auth:     { audience: ${job.targetAgent} }
  models:   { default: qwen-plus, budget: { dailyCny: 20 } }
  knowledge: [${knowledge} ]
  tools:
${tools}
  guardrails: { inputInjection: enforce, pii: mask }
  eval:     { dataset: ${job.targetAgent}/seed, gate: { minScore: 0.80, byTag: true } }
# spec-agent 生成 · ${job.jobId}`
}

export function flagView(flag?: string | null): { cls: string; text: string } | null {
  if (!flag) return null
  if (flag.startsWith('bad:')) return { cls: 'p-bad', text: flag.slice(4) }
  if (flag.startsWith('warn:')) return { cls: 'p-warn', text: flag.slice(5) }
  return { cls: 'p-warn', text: flag }
}

export function toggleTemplate(templates: string[], key: string): string[] {
  if (templates.includes(key)) return templates.length > 1 ? templates.filter((item) => item !== key) : templates
  return [...templates, key]
}

export function applyTakeover(job: DevflowJob, developer: string, at: string): Transition {
  if (!canTakeover(job)) return { error: '当前阶段不能人工接管' }
  let stage = job.stage
  if (stageIndex(stage) > stageIndex('GATE')) stage = 'GATE'
  if (stage === 'REVIEW') stage = 'BUILD'
  return {
    job: {
      ...job,
      status: 'HUMAN',
      stage,
      needReview: false,
      humanDevUser: developer,
      events: [...job.events, { at, actor: developer, summary: '人工接管开发' }],
    },
  }
}

export function applyHandback(job: DevflowJob, actor: string, at: string): Transition {
  if (!canHandback(job)) return { error: '只有人工开发中的任务可以交还' }
  return {
    job: {
      ...job,
      status: 'RUN',
      stage: 'REVIEW',
      events: [...job.events, { at, actor, summary: handbackLabel(job.mode) }],
    },
  }
}

export function applyAssist(job: DevflowJob, instruction: string, actor: string, at: string): Transition {
  if (!canAssist(job)) return { error: '只有人工开发中的任务可以请智能体帮忙' }
  const text = instruction.trim()
  if (!text) return { error: '请写明要它做什么' }
  return {
    job: {
      ...job,
      events: [
        ...job.events,
        { at, actor, summary: `请 dev-agent 帮忙：${text}` },
        { at, actor: 'dev-agent', summary: '按指令完成，推送 1 次提交' },
      ],
    },
  }
}

export function applyCancel(job: DevflowJob, actor: string, at: string): Transition {
  if (!canCancel(job)) return { error: '当前状态不能取消' }
  return {
    job: {
      ...job,
      status: 'CANCEL',
      events: [...job.events, { at, actor, summary: '取消任务' }],
    },
  }
}
