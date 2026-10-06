export type EnvName = 'dev' | 'test' | 'staging' | 'prod'
export type EnvFilter = 'all' | EnvName

export const PR_ENVS: EnvName[] = ['dev', 'test', 'staging', 'prod']
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
  ['trying', 'dev / test 在试'],
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
  config?: Record<string, string | number>
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
  return declared && env !== 'prod'
}

export function promotableEnvs(env: EnvFilter, version: number | null, labs: Partial<Record<string, number>> | undefined) {
  if (version == null) return []
  const candidates: EnvName[] = env === 'all' ? ['dev', 'test', 'staging'] : ['dev', 'test', 'staging'].includes(env) ? [env] : []
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
      text: `v${code.version} 是 ${code.createdBy || 'CI'} 从 prompts/ 带上来的（${code.commitMessage || `from git ${code.gitSha || ''}`}），还没在任何环境生效。先在 dev 或 test 试，再在 staging 生效。`,
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

export function variablesOf(prompt: unknown) {
  const text = typeof prompt === 'string'
    ? prompt
    : Array.isArray(prompt)
      ? prompt.map((message) => String((message as { content?: string }).content ?? '')).join('\n')
      : ''
  return [...new Set([...text.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((match) => match[1]))]
}
