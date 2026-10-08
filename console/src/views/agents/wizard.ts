export const NAME = /^[a-z][a-z0-9-]{1,38}[a-z0-9]$/

export interface WizardForm {
  name: string
  displayName: string
  ownerOrg: string
  ownerUser: string
  category: 'biz' | 'dev'
  template: string
  model: string
  dailyBudgetCny: number
  env: 'dev' | 'test' | 'staging' | 'prod'
}

export function nameError(name: string, taken = false): string {
  if (!name) return '必填'
  if (!NAME.test(name)) return '小写字母开头，3～40 位，只能用小写字母、数字、连字符'
  if (taken) return '名称已被占用'
  return ''
}

export type DevflowLayer = 'biz' | 'dev'
export type DevflowKind = 'CREATE' | 'CHANGE'
export type DevflowMode = 'AUTO' | 'COLLAB' | 'SCAFFOLD'

export interface DevflowForm {
  layer: DevflowLayer
  kind: DevflowKind
  mode: DevflowMode
  title: string
  agent: string
  goal: string
  template: string
  users: string
  io: string
  success: string
  tools: string[]
  knowledge: string[]
  dailyBudgetCny: number
  seedCount: number
}

export const DEVFLOW_MODES: Record<DevflowMode, [string, string]> = {
  AUTO: ['全托管', '研发员工写全部代码，人只在四个关口出手；人可随时推提交或接管'],
  COLLAB: ['人机协作', '每一轮 PR 必须有人工 approve 才进门禁；智能体不覆盖人写的代码'],
  SCAFFOLD: ['只生成骨架', '智能体出需求单、岗位说明书、评测集和骨架 PR，之后由人开发，写完提交门禁'],
}

export const DEVFLOW_TEMPLATES: [string, string][] = [
  ['tool-agent', '调用工具完成任务'],
  ['chat-rag', '知识问答 + 引用'],
  ['graph-agent', '多步推理（LangGraph）'],
  ['supervisor', '委派多个子智能体'],
  ['java-spring', 'Java · Spring Boot'],
]

export const DEVFLOW_TOOLS = ['git.log.read', 'git.tag.list', 'jira.issue.search', 'wecom.message.send', 'rag.search', 'work_order_create']
export const DEVFLOW_KBS = ['dev-standards', 'release-handbook', 'cs-faq', 'fault-manual']

export function blankDevflow(agent = '', kind: DevflowKind = 'CREATE'): DevflowForm {
  return {
    layer: 'biz',
    kind,
    mode: 'AUTO',
    title: '',
    agent,
    goal: '',
    template: 'tool-agent',
    users: '',
    io: '',
    success: '',
    tools: [],
    knowledge: [],
    dailyBudgetCny: 20,
    seedCount: 0,
  }
}

export function lockDevflowMode(form: DevflowForm): DevflowForm {
  return form.layer === 'dev' ? { ...form, mode: 'COLLAB' } : form
}

export function devflowStepError(step: number, form: DevflowForm, takenNames: string[] = []): string {
  if (step === 0) {
    if (!form.title.trim() || !form.goal.trim()) return '标题和目标必填'
    if (!NAME.test(form.agent)) return '智能体 ID 不合规'
    if (form.kind === 'CREATE' && takenNames.includes(form.agent)) return '该 ID 已存在；要改已有智能体请选「改造」'
    if (form.kind === 'CHANGE' && form.agent === 'meta-agent') return '元智能体只能由人直接修改'
  }
  if (step === 1 && (!form.users.trim() || !form.io.trim() || !form.success.trim())) return '场景、输入输出、成功标准必填'
  return ''
}

export function devflowBody(form: DevflowForm, ownerOrg: string) {
  const locked = lockDevflowMode(form)
  return {
    title: locked.title.trim(),
    targetAgent: locked.agent.trim(),
    layer: locked.layer === 'dev' ? 'DEV' as const : 'BIZ' as const,
    kind: locked.kind,
    mode: locked.mode,
    goal: [locked.goal.trim(), locked.users.trim(), locked.io.trim(), locked.success.trim()].filter(Boolean).join('\n'),
    template: locked.template,
    dailyBudgetCny: locked.dailyBudgetCny,
    seedCount: locked.seedCount,
    tools: [...locked.tools],
    knowledge: [...locked.knowledge],
    ownerOrg,
  }
}

export function registerBody(form: WizardForm) {
  return {
    name: form.name.trim(),
    displayName: form.displayName.trim() || form.name.trim(),
    category: form.category,
    ownerOrg: form.ownerOrg.trim(),
    ownerUser: form.ownerUser.trim(),
    env: form.env,
    runtime: 'code' as const,
    language: 'python',
    template: form.template,
    models: {
      default: form.model,
      fallback: [] as string[],
      dailyBudgetCny: form.dailyBudgetCny,
    },
  }
}
