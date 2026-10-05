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
      score: scoreText(record.scoreTotal),
      gate: record.gatePassed ?? null,
      time: stamp(record.releasedAt),
    }
  })
}

export function canRelease(agent: Pick<DrawerAgent, 'env' | 'gatePassed' | 'status'>) {
  return agent.env === 'staging' && agent.gatePassed === true && agent.status !== 'DRAFT' && agent.status !== 'OFFLINE'
}

export function kindLine(agent: { category?: string; runtime?: string; language?: string }) {
  const category = agent.category === 'biz' ? '业务智能体' : agent.category === 'dev' ? '研发智能体' : ''
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
