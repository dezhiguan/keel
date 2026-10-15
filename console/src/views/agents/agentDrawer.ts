import { agentType } from './employees'

const COLORS: Record<string, string> = {
  careermate: '#2ec4b6', askdb: '#5b9cf6', 'offshore-wind': '#34c38f', 'cs-bot': '#b48cf2', 'ops-copilot': '#ff7a45',
  'prd-agent': '#f1b44c', 'code-review': '#e36fae', 'test-gen': '#6fd3e3', 'ci-doctor': '#f46a6a', 'dev-copilot': '#ffb08f',
}

export interface DrawerVersion {
  version?: string
  env?: string
  gatePassed?: boolean | null
  scoreTotal?: number | null
  releasedAt?: string
}

export interface DrawerAgent {
  name?: string
  displayName?: string
  version?: string
  env?: string
  score?: number | null
  gatePassed?: boolean | null
  status?: string
  versions?: DrawerVersion[]
}

export function executionRows(execution?: { current?: string; target?: string; constraints?: string } | null) {
  return [
    ['现在', execution?.current || '未声明'],
    ['目标', execution?.target || '未声明'],
    ['约束', execution?.constraints || '以 agent.yaml 为准'],
  ] as const
}

export function memoryRows(memory?: { session?: string; user?: string; task?: string; experience?: string } | null) {
  return [
    ['会话记忆', memory?.session || '未接入'],
    ['用户记忆', memory?.user || '未接入'],
    ['任务记忆', memory?.task || '未接入'],
    ['经验记忆', memory?.experience || '未接入'],
  ] as const
}

/** 原型记忆表：关闭、未接入用灰胶囊，候选用人审色，已开启用绿色。 */
export function memoryTone(value: string) {
  if (/不允许|不需要|关闭|—|未接入|未声明/.test(value)) return 'p-mute'
  if (/候选/.test(value)) return 'p-warn'
  return 'p-ok'
}

const CORE_ELEMENTS = new Set(['模型自己调工具', 'agent loop', '记忆', '有版本的提示词', '评测'])

/** 原型要素格：通过打勾、缺失打叉、没有运行证据的显示短注。 */
export function elementCell(item: { name: string; passed?: boolean | null; detail?: string | null }, index: number) {
  const state = item.passed === true ? 'ok' : item.passed === false ? 'no' : 'mid'
  const mark = item.passed === true ? '✓' : item.passed === false ? '✗' : '·'
  return {
    label: `${index + 1}. ${item.name}`,
    cls: CORE_ELEMENTS.has(item.name) ? `${state} core` : state,
    mark,
    note: item.passed == null ? elementNote(item.detail) : '',
  }
}

function elementNote(detail?: string | null) {
  const text = detail ?? ''
  if (text.includes('实测')) return '有，未实测'
  if (text.includes('无高风险')) return '—'
  if (text.includes('未声明')) return '未声明'
  if (text.includes('待')) return '待验证'
  return text
}

export function channelMethod(kind?: string) {
  if (kind === 'HOSTED') return '独立成站'
  if (kind === 'EMBED') return '对话气泡'
  if (kind === 'MINIPROGRAM') return '小程序 SDK'
  return '网页 SDK'
}

export function channelIdentity(kind: string | undefined, identity?: string) {
  if (identity === 'OPENID') return 'openid 映射'
  if (identity === 'ANONYMOUS') return '匿名访客'
  if (kind === 'HOSTED') return '登录用户（换票）'
  return '业务系统用户（换票）'
}

export interface VersionRow {
  version: string
  env: string
  change: string
  score: string
  gate: boolean | null
  time: string
}

export function avatarColor(name = '') {
  return COLORS[name] ?? '#8a97ab'
}

/** 原型头像取「 · 」后面的第一个字，小职 · 求职助手 → 求。 */
export function avatarLetter(displayName?: string) {
  const text = (displayName ?? '').replace(/^.*·\s*/, '').trim()
  return text.slice(0, 1) || '·'
}

export function scoreText(value?: number | null) {
  return value == null ? '—' : value.toFixed(2)
}

export function cardCost(value?: number | null) {
  if (value == null || Number.isNaN(value)) return '—'
  if (value === 0) return '¥0.0'
  if (Math.abs(value) >= 0.1) return `¥${value.toFixed(1)}`
  const digits = Math.abs(value) >= 0.01 ? 2 : 4
  return `¥${value.toFixed(digits)}`
}

/** 原型卡片：业务/研发、语言、环境、版本，有编排时再加一枚。 */
export function cardChips(agent: {
  category?: string | null
  runtime?: string | null
  language?: string | null
  env?: string | null
  version?: string | null
  delegateCount?: number | null
}) {
  const chips: string[] = []
  if (agent.category === 'biz') chips.push('业务')
  else if (agent.category === 'dev') chips.push('研发')
  const language = agent.runtime === 'dify' ? 'Dify' : languageName(agent.language)
  chips.push(agent.delegateCount ? `${language} · 多智能体` : language)
  if (agent.env) chips.push(agent.env)
  if (agent.version) chips.push(agent.version)
  if (agent.delegateCount) chips.push(`编排 ${agent.delegateCount} 个`)
  return chips
}

function languageName(language?: string | null) {
  const raw = (language ?? '').toLowerCase()
  if (raw === 'java') return 'Java'
  if (raw === 'python') return 'Python'
  return language || '—'
}

export function stamp(iso?: string | null) {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function seenAgo(iso?: string | null, now = Date.now()) {
  if (!iso) return '—'
  const sec = Math.round((now - new Date(iso).getTime()) / 1000)
  if (Number.isNaN(sec)) return '—'
  if (sec < 1) return '刚刚'
  if (sec < 60) return `${sec} 秒前`
  const minutes = Math.round(sec / 60)
  if (minutes < 60) return `${minutes} 分钟前`
  if (minutes < 1440) return `${Math.round(minutes / 60)} 小时前`
  return `${Math.round(minutes / 1440)} 天前`
}

export function versionRows(agent: DrawerAgent): VersionRow[] {
  const records = agent.versions ?? []
  if (!records.length) {
    if (!agent.version) return []
    return [{
      version: agent.version,
      env: agent.env || '—',
      change: '当前版本',
      score: scoreText(agent.score),
      gate: agent.gatePassed ?? null,
      time: '—',
    }]
  }
  return records.map((record) => {
    const current = !!record.version && record.version === agent.version && (!agent.env || !record.env || record.env === agent.env)
    return {
      version: record.version || '—',
      env: record.env || '—',
      change: current ? '当前版本' : '—',
      score: scoreText(record.scoreTotal ?? (current ? agent.score : null)),
      gate: record.gatePassed ?? (current ? agent.gatePassed ?? null : null),
      time: stamp(record.releasedAt),
    }
  })
}

export function canRelease(agent: Pick<DrawerAgent, 'env' | 'gatePassed' | 'status'>) {
  return agent.env === 'staging' && agent.gatePassed === true && agent.status !== 'DRAFT' && agent.status !== 'OFFLINE'
}

export function kindLine(agent: { name?: string; category?: string | null; layer?: string | null; runtime?: string; language?: string }) {
  const type = agentType(agent)
  const category = type === 'meta' ? '元智能体' : type === 'biz' ? '业务智能体' : type === 'dev' ? '研发智能体' : ''
  const language = agent.runtime === 'dify' ? 'Dify' : (agent.language ?? '')
  return [category, language].filter(Boolean).join(' · ') || '—'
}

const RESOURCE_LABEL: Record<string, string> = {
  litellm_key: '薄网关 虚拟 Key',
  langfuse: 'Langfuse',
  secret: 'K8s Secret',
  dataset: 'Langfuse 数据集',
  oauth_client: 'OAuth 客户端',
}

export function resourceLabel(type?: string) {
  return (type && RESOURCE_LABEL[type]) || type || '—'
}

export function resourceStatus(type?: string, status?: string | null) {
  const raw = (status ?? '').toUpperCase()
  if (raw === 'ACTIVE') {
    if (type === 'secret' || type === 'langfuse') return { cls: 'p-ok', label: '已写入' }
    if (type === 'dataset') return { cls: 'p-ok', label: '已导入' }
    if (type === 'oauth_client') return { cls: 'p-ok', label: '已注册' }
    return { cls: 'p-ok', label: '有效' }
  }
  if (raw === 'REVOKED') {
    const removed = type === 'secret' || type === 'dataset'
    return { cls: 'p-mute', label: removed ? '已删除' : '已吊销' }
  }
  if (raw === 'REVOKE_FAILED') return { cls: 'p-bad', label: '回收失败' }
  return { cls: 'p-mute', label: status || '—' }
}

export function toolChip(value: string) {
  const suffix = ' · 高 · 需审批'
  if (value.endsWith(suffix)) return { lead: value.slice(0, -suffix.length), high: true }
  return { lead: value, high: false }
}

/** The drawer is one instance for every agent. Only the agent whose probe is in flight shows 自检中. */
export function isSelfChecking(checking: readonly string[], agent: string) {
  return agent !== '' && checking.includes(agent)
}

export function withEvalGate<T extends { score?: number | null; selfCheck?: { passed?: boolean | null; items?: { name?: string; passed?: boolean | null; detail?: string | null }[] } }>(agent: T): T {
  const items = agent.selfCheck?.items
  if (!items || agent.score == null) return agent
  const min = 0.85
  return {
    ...agent,
    selfCheck: {
      ...agent.selfCheck,
      items: items.map((item) => {
        if (item.name !== '评测门禁' || (item.detail && item.detail !== '未运行')) return item
        const ok = agent.score != null && agent.score >= min
        return { ...item, passed: ok, detail: `${scoreText(agent.score)} ${ok ? '≥' : '<'} ${min}` }
      }),
    },
  }
}

const SOURCE_LABEL: Record<string, string> = {
  k8s: 'K8s watch',
  heartbeat: 'SDK 心跳',
  probe: 'Dify API 探活',
}

export function sourceLabel(source?: string) {
  return (source && SOURCE_LABEL[source]) || source || '—'
}

export function readyPill(ready?: boolean, source?: string) {
  if (source === 'probe') return ready ? { cls: 'p-ok', label: '200' } : { cls: 'p-bad', label: '失败' }
  if (ready) return { cls: 'p-ok', label: 'Ready' }
  return { cls: 'p-bad', label: source === 'k8s' ? 'CrashLoop' : '未就绪' }
}

export function highlightYaml(yaml: string) {
  const escaped = yaml.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return escaped
    .replace(/^(\s*-?\s*)([A-Za-z_][\w.-]*)(?=\s*:)/gm, '$1<span class="k">$2</span>')
    .replace(/(#[^\n]*)$/gm, '<span class="c">$1</span>')
}
