export const NAME = /^[a-z][a-z0-9-]{1,38}[a-z0-9]$/

export interface WizardForm {
  name: string
  displayName: string
  ownerOrg: string
  ownerUser: string
  category: 'biz' | 'dev'
  language: 'python' | 'java'
  endpoint?: string
  template: string
  model: string
  dailyBudgetCny: number
  env: 'dev' | 'test' | 'staging' | 'prod'
  tools: string[]
  knowledgeBases: string[]
  approvalPolicyId: string
  promptType: 'text' | 'chat'
}

export const WIZARD_TEMPLATES: [string, string, string][] = [
  ['echo', '回声', '一问一答，用来打通链路和审计'],
  ['chat-rag', '知识库问答', '客服、FAQ、文档问答'],
  ['tool-agent', '工具调用', '代码评审、CI 诊断'],
  ['graph-agent', '多步推理', '多步图'],
  ['supervisor', '多智能体', '编排其他已注册智能体'],
  ['java-spring', 'Java', 'Spring Boot + keel-starter'],
]

/** Java 只留 java-spring 和 supervisor；Python 不出现 java-spring。 */
export function templatesFor(language: 'python' | 'java') {
  return WIZARD_TEMPLATES.filter(([id]) => language === 'java' ? id === 'java-spring' || id === 'supervisor' : id !== 'java-spring')
}

export function templateForLanguage(language: 'python' | 'java', template: string) {
  const allowed = templatesFor(language).map(([id]) => id)
  if (allowed.includes(template)) return template
  return language === 'java' ? 'java-spring' : 'echo'
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
  delegates: string[]
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
export const GENERAL_AGENT_TEMPLATES = ['tool-agent', 'chat-rag', 'graph-agent', 'supervisor']

export const DEV_ROLE_TEMPLATES: Record<string, string> = {
  'dev-lead': 'supervisor',
  'spec-agent': 'graph-agent',
  'dev-agent': 'graph-agent',
  'eval-agent': 'tool-agent',
  'code-review': 'tool-agent',
  'ci-doctor': 'tool-agent',
  'release-agent': 'tool-agent',
}
export const DEV_ROLE_TOOLS: Record<string, string[]> = {
  'dev-lead': ['devflow.job.read', 'devflow.stage.report', 'devflow.batch.read'],
  'spec-agent': ['devflow.catalog.search', 'devflow.stage.report'],
  'dev-agent': ['sandbox.run', 'git.repo.create', 'git.branch.push', 'git.pr.open'],
  'eval-agent': ['devflow.job.read', 'sandbox.run', 'devflow.stage.report'],
  'code-review': ['git.pr.diff', 'git.pr.comment'],
  'ci-doctor': ['ci.log.fetch', 'keel.gate.report.read'],
  'release-agent': ['ci.workflow.dispatch', 'keel.gate.report.read', 'git.pr.merge'],
}

export const DEVFLOW_TOOLS = ['git.log.read', 'git.tag.list', 'jira.issue.search', 'wecom.message.send', 'rag.search', 'work_order_create']
export const DEVFLOW_KBS = ['dev-standards', 'release-handbook', 'cs-faq', 'fault-manual']

export function blankDevflow(agent = '', kind: DevflowKind = 'CREATE'): DevflowForm {
  return {
    layer: 'dev',
    kind,
    mode: 'COLLAB',
    title: '',
    agent,
    goal: '',
    template: DEV_ROLE_TEMPLATES[agent] || 'tool-agent',
    users: '',
    io: '',
    success: '',
    tools: [...(DEV_ROLE_TOOLS[agent] || [])],
    knowledge: [],
    delegates: [],
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
    if (form.layer === 'dev' && !GENERAL_AGENT_TEMPLATES.includes(form.template)) return '请选择支持的 Python 智能体模板'
    if (form.layer === 'dev' && DEV_ROLE_TEMPLATES[form.agent] && form.template !== DEV_ROLE_TEMPLATES[form.agent]) return '该岗位必须使用原型指定的模板'
    if (form.layer === 'dev' && DEV_ROLE_TEMPLATES[form.agent] &&
      (form.tools.length !== DEV_ROLE_TOOLS[form.agent].length ||
      form.tools.some(tool => !DEV_ROLE_TOOLS[form.agent].includes(tool)))) return '岗位工具必须与原型清单完全一致'
  }
  if (step === 1 && (!form.users.trim() || !form.io.trim() || !form.success.trim())) return '场景、输入输出、成功标准必填'
  if (step === 1 && form.template === 'supervisor' && !DEV_ROLE_TEMPLATES[form.agent] && !form.delegates.length) return '协作模板至少选择一个已登记的子智能体'
  if (step === 1 && (form.delegates.length > 5 || form.delegates.includes(form.agent))) return '子智能体清单无效'
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
    goal: locked.goal.trim(),
    users: locked.users.trim(),
    io: locked.io.trim(),
    success: locked.success.trim(),
    execution: true,
    template: locked.template,
    dailyBudgetCny: locked.dailyBudgetCny,
    seedCount: locked.seedCount,
    tools: [...locked.tools],
    knowledge: [...locked.knowledge],
    delegates: [...locked.delegates],
    ownerOrg,
  }
}

/** Concrete models a logical name may select. The key must allow them or the call cannot leave the gateway. */
export function logicalTargets(model: string) {
  if (model === 'chat-default' || model === 'chat-cheap' || model === 'long-context') {
    return ['qwen3.8-flash', 'deepseek-flash']
  }
  return []
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
    language: form.language,
    template: form.template,
    ...(form.endpoint?.trim() ? { endpoint: form.endpoint.trim() } : {}),
    models: {
      default: form.model,
      fallback: logicalTargets(form.model),
      dailyBudgetCny: form.dailyBudgetCny,
    },
    tools: [...form.tools],
    knowledgeBases: [...form.knowledgeBases],
    ...(form.approvalPolicyId ? { approvalPolicyId: form.approvalPolicyId } : {}),
    promptType: form.promptType === 'text' ? 'text' as const : 'chat' as const,
  }
}
