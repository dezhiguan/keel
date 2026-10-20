export type EnvName = 'dev' | 'test' | 'staging' | 'prod'
export type EnvFilter = 'all' | EnvName

export const PR_ENVS: EnvName[] = ['staging', 'prod']
export const PR_LABEL: Record<EnvName, string> = {
  dev: 'dev',
  test: 'test',
  staging: 'staging',
  prod: 'production',
}

export const STATUS_FILTERS: [string, string][] = [
  ['all', '全部'],
  ['ready', '待发布'],
  ['pending', '回归中'],
  ['failed', '回归未过'],
  ['code', '代码里有新版本'],
  ['drift', '漂移'],
  ['same', '一致'],
  ['unreleased', '未发布'],
]

export interface PromptState {
  id: string
  label: string
  tone: string
}

export interface PromptVersion {
  version: number
  labels?: string[]
  commitMessage?: string | null
  source?: string
  createdAt?: string | null
  createdBy?: string | null
  sha256?: string
}

export interface CodeVersion {
  version: number
  gitSha?: string | null
  commitMessage?: string | null
  createdBy?: string | null
}

export interface PromptRelease {
  version: number
  at?: string
  gate?: string | null
}

export interface PromptSection {
  kind: string
  text: string
  id?: string
  dimension?: string
}

export interface PromptVariableSchema {
  name: string
  type?: string
  source?: string
  required?: boolean
  limit?: string
  pii?: boolean
}

export interface PromptSample {
  name: string
  variables?: Record<string, string>
}

export interface PromptStructure {
  sections?: PromptSection[]
  variables?: PromptVariableSchema[]
  outputContract?: Record<string, unknown> | null
  samples?: PromptSample[]
}

export interface PromptConfig {
  temperature?: number | string
  max_tokens?: number | string
  top_p?: number | string
  keel?: PromptStructure
}

export const SECTION_LABEL: Record<string, string> = {
  role: '角色',
  task: '任务',
  constraint: '约束',
  tool: '工具',
  output: '输出',
  example: '示例',
}

export interface PromptDetailModel {
  agent: string
  name: string
  type: string
  declared?: boolean
  gate?: string
  gateNote?: string | null
  drift?: { env: string; text: string }[]
  codeVersion?: CodeVersion | null
  labs?: Partial<Record<string, number>>
  states?: PromptState[]
  releases?: PromptRelease[]
  versions?: PromptVersion[]
  config?: PromptConfig
  langfuseUrl?: string | null
  metricsUrl?: string | null
}

export function visibleEnvs(env: EnvFilter): EnvName[] {
  return env === 'all' ? PR_ENVS : [env]
}

export function matchesStatus(states: PromptState[] | undefined, filter: string) {
  if (filter === 'all') return true
  const ids = (states ?? []).map((state) => state.id)
  if (filter === 'failed') return ids.includes('failed') || ids.includes('invalid')
  return ids.includes(filter)
}

export function canEdit(env: EnvFilter, declared = true) {
  return declared && (env === 'all' || env === 'staging')
}

export function promotableEnvs(env: EnvFilter, version: number | null, labs: Partial<Record<string, number>> | undefined): 'staging'[] {
  if (version == null) return []
  const candidates: 'staging'[] = env === 'all' || env === 'staging' ? ['staging'] : []
  return candidates.filter((name) => labs?.[name] !== version)
}

export function canRollback(env: EnvFilter, version: number | null, labs: Partial<Record<string, number>> | undefined, releases: PromptRelease[] | undefined) {
  if (version == null || (env !== 'all' && env !== 'prod')) return false
  if (!labs?.prod || labs.prod === version) return false
  return (releases ?? []).some((release) => release.version === version)
}

export function formatWhen(iso: string | null | undefined, now = new Date()) {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('zh-CN', {
      timeZone: 'Asia/Shanghai',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    }).formatToParts(date).map((part) => [part.type, part.value]),
  )
  const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' })
  if (day.format(date) === day.format(now)) return `今天 ${parts.hour}:${parts.minute}`
  return `${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}

export function shortSha(sha: string | null | undefined) {
  if (!sha) return ''
  const hex = sha.replace(/^sha256:/, '')
  return `sha256:${hex.slice(0, 8)}`
}

export function highlightParts(text: string) {
  const parts: { key: string; variable: boolean; text: string }[] = []
  const pattern = /\{\{\s*\w+\s*\}\}/g
  let cursor = 0
  const push = (variable: boolean, value: string) => parts.push({ key: `${parts.length}-${variable}`, variable, text: value })
  for (const match of text.matchAll(pattern)) {
    const index = match.index ?? 0
    if (index > cursor) push(false, text.slice(cursor, index))
    push(true, match[0])
    cursor = index + match[0].length
  }
  if (cursor < text.length) push(false, text.slice(cursor))
  return parts
}

export function stateIds(detail: PromptDetailModel) {
  return (detail.states ?? []).map((state) => state.id)
}

export interface Banner {
  tone: '' | 'bad' | 'warn' | 'ok'
  title: string
  text: string
  action?: { kind: 'rollback' | 'pick'; version: number; label: string }
}

export function banners(detail: PromptDetailModel, env: EnvFilter): Banner[] {
  const show = (name: string) => env === 'all' || env === name
  const ids = stateIds(detail)
  const staging = detail.labs?.staging
  const prod = detail.labs?.prod
  const out: Banner[] = []
  for (const drift of detail.drift ?? []) {
    if (!show(drift.env)) continue
    const last = [...(detail.releases ?? [])].reverse().find((release) => release.gate !== '回滚') ?? detail.releases?.at(-1)
    out.push({
      tone: 'bad',
      title: '漂移',
      text: drift.text,
      action: drift.env === 'prod' && last ? { kind: 'rollback', version: last.version, label: `回到发布版本 v${last.version}` } : undefined,
    })
  }
  if ((ids.includes('failed') || ids.includes('invalid')) && (show('staging') || show('prod'))) {
    out.push({
      tone: 'bad',
      title: ids.includes('invalid') ? '回归作废' : '回归未过',
      text: `staging v${staging ?? '—'}：${detail.gateNote || '回归未过'}。keel release 会被拒绝（PROMPT_NOT_GATED），prod 继续用 v${prod ?? '—'}。改好后保存新版本并在 staging 生效，会重新回归。`,
    })
  }
  if (ids.includes('pending') && (show('staging') || show('prod'))) {
    out.push({
      tone: 'warn',
      title: '回归中',
      text: `staging v${staging ?? '—'} 已生效，回归实验正在跑。实验里只要有一条模型调用用的不是 v${staging ?? '—'}，这次实验作废并在 1 分钟后重跑。请手动运行 keel gate。`,
    })
  }
  if (detail.codeVersion && env !== 'prod') {
    const code = detail.codeVersion
    out.push({
      tone: 'warn',
      title: '代码里有新版本',
      text: `v${code.version} 是 ${code.createdBy || 'CI'} 从 prompts/ 带上来的（${code.commitMessage || `from git ${code.gitSha || ''}`}），还没在任何环境生效。先在 staging 验证，再通过评测发布到 prod。`,
      action: { kind: 'pick', version: code.version, label: `看 v${code.version}` },
    })
  }
  if (ids.includes('ready') && (show('staging') || show('prod'))) {
    out.push({
      tone: 'ok',
      title: '待发布',
      text: `staging v${staging} 回归已通过，prod 还在 v${prod}。下一次 keel release 会把 production 标签挪到 v${staging}。`,
    })
  }
  if (ids.includes('unreleased') && (show('staging') || show('prod'))) {
    out.push({
      tone: '',
      title: '未发布',
      text: `${detail.agent} 还没有发布到 prod，第一次 keel release 会给 staging 当前版本打上 production 标签。`,
    })
  }
  if (env === 'prod') {
    out.push({
      tone: '',
      title: '',
      text: 'prod 只读：没有编辑和生效按钮，只能通过 keel release 切换，或回滚到发布过的版本。线上读 production 标签，缓存 60 秒。',
    })
  }
  return out
}

export const CHAT_ROLES = ['system', 'user', 'assistant'] as const

export interface PromptDraft {
  role: string
  content: string
}

export interface ConfigDraft {
  temperature: string
  maxTokens: string
  topP: string
}

export function starterDrafts(type: string): PromptDraft[] {
  if (type === 'chat') {
    return [
      { role: 'system', content: '你是这个智能体。用简短、清楚的中文回答。不要编造没有依据的内容。' },
      { role: 'user', content: '{{question}}' },
    ]
  }
  return [{ role: 'text', content: '用简短、清楚的中文回答。\n\n用户说：{{question}}' }]
}

export function editingDrafts(type: string, prompt: unknown): PromptDraft[] {
  if (typeof prompt === 'string' && prompt.length) return [{ role: 'text', content: prompt }]
  if (Array.isArray(prompt) && prompt.length) {
    return prompt.map((message) => ({
      role: String((message as { role?: string }).role ?? 'user'),
      content: String((message as { content?: string }).content ?? ''),
    }))
  }
  return starterDrafts(type)
}

export function rolesFor(role: string) {
  return (CHAT_ROLES as readonly string[]).includes(role) ? [...CHAT_ROLES] : [role, ...CHAT_ROLES]
}

export function draftError(type: string, drafts: PromptDraft[]) {
  if (!drafts.length) return '至少保留一条消息'
  if (type === 'chat') {
    for (const draft of drafts) {
      if (!(CHAT_ROLES as readonly string[]).includes(draft.role)) return '消息角色只能是 system、user、assistant'
      if (!draft.content.trim()) return '每条消息都要有内容'
    }
    return ''
  }
  return drafts[0]?.content.trim() ? '' : '提示词不能为空'
}

export function structureOf(config?: PromptConfig | null): PromptStructure {
  return config?.keel && typeof config.keel === 'object' ? config.keel : {}
}

export function removedConstraints(previous?: PromptSection[], next?: PromptSection[]) {
  const kept = new Set((next ?? []).filter((item) => item.kind === 'constraint').map((item) => item.text))
  return (previous ?? [])
    .filter((item) => item.kind === 'constraint' && item.text && !kept.has(item.text))
    .map((item) => ({ id: item.id ?? '', text: item.text, dimension: item.dimension ?? '' }))
}

export function removedConstraintMessage(items: { text: string; dimension?: string }[]) {
  if (!items.length) return ''
  const parts = items.map((item) => `「${item.text}」（${item.dimension || '未标维度'}）`)
  return `删除了约束：${parts.join('；')}。staging 回归重点看这些维度。`
}

export function configDraft(config?: PromptConfig | null): ConfigDraft {
  return {
    temperature: config?.temperature == null || config.temperature === '' ? '' : String(config.temperature),
    maxTokens: config?.max_tokens == null || config.max_tokens === '' ? '' : String(config.max_tokens),
    topP: config?.top_p == null || config.top_p === '' ? '' : String(config.top_p),
  }
}

function readNumber(raw: string, label: string, min: number, max: number): { error: string } | { value?: number } {
  const text = raw.trim()
  if (!text) return {}
  const value = Number(text)
  if (!Number.isFinite(value) || value < min || value > max) return { error: `${label} 要在 ${min} 到 ${max} 之间` }
  return { value }
}

export function configPayload(draft: ConfigDraft, keel?: PromptStructure | null): { error: string } | { config: PromptConfig } {
  const temperature = readNumber(draft.temperature, 'temperature', 0, 2)
  if ('error' in temperature) return temperature
  const topP = readNumber(draft.topP, 'top_p', 0, 1)
  if ('error' in topP) return topP
  const tokens = draft.maxTokens.trim()
  let maxTokens: number | undefined
  if (tokens) {
    if (!/^[1-9]\d*$/.test(tokens)) return { error: 'max_tokens 要是正整数' }
    maxTokens = Number(tokens)
  }
  const config: PromptConfig = {}
  if (temperature.value != null) config.temperature = temperature.value
  if (maxTokens != null) config.max_tokens = maxTokens
  if (topP.value != null) config.top_p = topP.value
  if (keel && Object.keys(keel).length) config.keel = keel
  return { config }
}

export function unchangedPrompt(currentPrompt: unknown, currentConfig: PromptConfig | null | undefined, nextPrompt: unknown, nextConfig: PromptConfig) {
  if (currentPrompt == null) return false
  const current = configPayload(configDraft(currentConfig), currentConfig?.keel)
  const stored = 'config' in current ? current.config : {}
  return JSON.stringify(currentPrompt) === JSON.stringify(nextPrompt) && JSON.stringify(stored) === JSON.stringify(nextConfig)
}

export function variablesOf(prompt: unknown) {
  const text = typeof prompt === 'string'
    ? prompt
    : Array.isArray(prompt)
      ? prompt.map((message) => String((message as { content?: string }).content ?? '')).join('\n')
      : ''
  return [...new Set([...text.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((match) => match[1]))]
}
