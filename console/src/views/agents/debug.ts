import type { AgentDetail } from '@/api/agents'
import type { TraceNode } from '@/api/traces'

export type DebugMode = 'chat' | 'task' | 'service' | 'schedule'

export const MODE_LABEL: Record<DebugMode, { name: string; hint: string }> = {
  chat: { name: '对话式', hint: '用户多轮问答' },
  task: { name: '任务式', hint: '提交一件事，看执行步骤和产出' },
  service: { name: '后台式', hint: '没有用户入口，由事件、其他系统或上级智能体调用' },
  schedule: { name: '定时任务', hint: '按计划自动运行' },
}

export function debugMode(agent?: Pick<AgentDetail, 'interaction'> | null): DebugMode {
  const mode = agent?.interaction?.mode
  return mode && mode in MODE_LABEL ? mode : 'chat'
}

/** 不能调试时返回原因；可以调试返回 null。 */
export function debugBlocked(status?: string | null) {
  if (status === 'DRAFT') return '还没注册，不能调试。先 keel register 或在向导里完成注册。'
  if (status === 'RETIRED') return '已下线，虚拟 Key 已吊销，不能再调用。历史运行仍可在链路追踪里查看。'
  return null
}

export function debugWarning(status?: string | null) {
  if (status === 'OFFLINE') return '当前离线（没有就绪实例），调用大概率失败。'
  if (status === 'DEGRADED') return '当前降级运行，回复可能来自降级模型。'
  return null
}

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六', '周日']
const pad = (value: string) => value.padStart(2, '0')
const isNum = (value: string) => /^\d+$/.test(value)

/** 只翻译常见写法，其余原样返回 null，由页面只显示 cron 原文。 */
export function describeCron(expr?: string | null): string | null {
  const parts = (expr ?? '').trim().split(/\s+/)
  if (parts.length !== 5) return null
  const [min, hour, day, month, week] = parts
  const everyMin = /^\*\/(\d+)$/.exec(min)
  if (everyMin && hour === '*' && day === '*' && month === '*' && week === '*') return `每 ${everyMin[1]} 分钟`
  if (!isNum(min)) return null
  const everyHour = /^\*\/(\d+)$/.exec(hour)
  if (everyHour && day === '*' && month === '*' && week === '*') return `每 ${everyHour[1]} 小时的第 ${min} 分`
  if (hour === '*' && day === '*' && month === '*' && week === '*') return `每小时第 ${min} 分`
  if (!isNum(hour)) return null
  const at = `${pad(hour)}:${pad(min)}`
  if (month !== '*') return null
  if (day === '*' && week === '*') return `每天 ${at}`
  if (day === '*' && week === '1-5') return `工作日 ${at}`
  if (day === '*' && isNum(week) && Number(week) <= 7) return `每${WEEK[Number(week)]} ${at}`
  if (isNum(day) && week === '*') return `每月 ${day} 日 ${at}`
  return null
}

export interface StepRow {
  id: string
  name: string
  type: string
  depth: number
  durationMs: number | null
  status: NonNullable<TraceNode['status']>
  output: string
}

const TYPE_LABEL: Record<string, string> = {
  agent: '智能体', generation: '模型', tool: '工具', retriever: '检索', guardrail: '护栏', event: '事件',
}

/** 链路节点按开始时间排成步骤；根 agent 节点就是整次运行，不单列。 */
export function stepRows(nodes: TraceNode[] = []): StepRow[] {
  return [...nodes]
    .filter((node) => (node.depth ?? 0) > 0)
    .sort((a, b) => (a.startMs ?? 0) - (b.startMs ?? 0))
    .map((node, index) => ({
      id: node.id ?? String(index),
      name: node.shortName || node.name || '—',
      type: TYPE_LABEL[node.type ?? ''] ?? node.type ?? '—',
      depth: Math.max(0, (node.depth ?? 1) - 1),
      durationMs: node.durationMs ?? null,
      status: node.status ?? 'ok',
      output: node.outputSummary && node.outputSummary !== node.name ? node.outputSummary : '',
    }))
}

export const DEBUG_PAGE = { sessions: 10, messages: 10, events: 20 } as const
export const SESSION_TTL_MS = 7 * 86_400_000
export const EVENT_CAP = 500

export interface DebugMessage {
  role: 'user' | 'agent'
  text: string
  meta?: string
  traceId?: string | null
  error?: boolean
}

export type DebugEventKind = 'guardrail' | 'step' | 'tool' | 'token' | 'final' | 'error'

export interface DebugEvent {
  kind: DebugEventKind
  text: string
  hit?: boolean
}

export interface DebugSession {
  id: string
  title: string
  messages: DebugMessage[]
  events: DebugEvent[]
  at: number
  messageCount: number
  older?: number | null
}

export interface SseFrame {
  event: string
  data: string
}

export interface LiveFrame {
  kind: DebugEventKind
  text: string
  hit?: boolean
  token?: string
  answer?: string
  traceId?: string | null
  blocked?: boolean
}

export function takeSseFrames(buffer: string): { frames: SseFrame[]; rest: string } {
  const frames: SseFrame[] = []
  let rest = buffer.replace(/\r\n/g, '\n')
  while (true) {
    const split = rest.indexOf('\n\n')
    if (split < 0) break
    const raw = rest.slice(0, split)
    rest = rest.slice(split + 2)
    let event = 'message'
    const data: string[] = []
    for (const line of raw.split('\n')) {
      if (!line || line.startsWith(':')) continue
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (raw.trim()) frames.push({ event, data: data.join('\n') })
  }
  return { frames, rest }
}

export function describeLiveFrame(event: string, data: string): LiveFrame | null {
  let body: Record<string, unknown> = {}
  try {
    body = data ? JSON.parse(data) as Record<string, unknown> : {}
  } catch {
    body = { message: data }
  }
  const text = (key: string) => (typeof body[key] === 'string' ? body[key] as string : '')
  if (event === 'token') return { kind: 'token', text: text('text'), token: text('text') }
  if (event === 'step') return { kind: 'step', text: text('name') || data }
  if (event === 'tool') return { kind: 'tool', text: text('name') || data }
  if (event === 'final') return { kind: 'final', text: text('trace_id'), answer: text('answer'), traceId: text('trace_id') || null }
  if (event === 'error') {
    const message = text('message') || data
    const blocked = message.includes('GUARD_') || text('code').includes('GUARD_')
    return { kind: 'error', text: message, blocked }
  }
  if (event === 'guardrail') {
    const actions: Record<string, string> = { pass: '通过', off: '未启用', shadow: '命中 · 仅标记', blocked: '命中 · 已拦截', stripped: '命中 · 已剔除', approval: '命中 · 等待审批' }
    const summary = body.action ? `${text('name')} · ${text('mode')} · ${actions[text('action')] || text('action')}` : ''
    return { kind: 'guardrail', text: text('message') || text('text') || summary || data, hit: body.hit === true || data.includes('命中') }
  }
  if (event === 'suspend') return { kind: 'step', text: text('prompt') || '等待继续', answer: text('prompt') || '等待继续', traceId: text('trace_id') || null }
  return null
}

export type SseState = 'idle' | 'streaming' | 'done' | 'stopped' | 'blocked' | 'failed' | 'suspended'

export const SSE_LABEL: Record<SseState, { text: string; cls: string }> = {
  idle: { text: '空闲', cls: 'p-mute' },
  streaming: { text: '流式中', cls: 'p-ok' },
  done: { text: '已完成', cls: 'p-mute' },
  stopped: { text: '已停止', cls: 'p-warn' },
  suspended: { text: '等待人工处理', cls: 'p-warn' },
  blocked: { text: '已拦截', cls: 'p-bad' },
  failed: { text: '失败', cls: 'p-bad' },
}

export function newDebugSessionId() {
  const bytes = crypto.getRandomValues(new Uint8Array(4))
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `ss_${hex}`
}

export function createSession(now = Date.now()): DebugSession {
  return { id: newDebugSessionId(), title: '', messages: [], events: [], at: now, messageCount: 0 }
}

export function debugStoreKey(userId: string, agent: string, env: string) {
  return `keel-debug:${userId}:${agent}:${env}`
}

export function byRecent<T extends { at: number }>(items: T[]) {
  return [...items].sort((left, right) => right.at - left.at)
}

export function pruneSessions<T extends { at: number }>(items: T[], now = Date.now(), keepMs = SESSION_TTL_MS) {
  return items.filter((item) => item.at >= now - keepMs)
}

export function visibleHead<T>(items: T[], shown: number) {
  return items.slice(0, Math.max(0, shown))
}

export function visibleTail<T>(items: T[], shown: number) {
  if (shown >= items.length) return items
  return items.slice(items.length - Math.max(0, shown))
}

export function pageStatus(shown: number, total: number, unit: string, dir: 'up' | 'down', loading = false) {
  if (loading) return '加载中…'
  if (shown >= total) return `已全部加载 · 共 ${total} ${unit}`
  return `${dir === 'up' ? '↑ 上滑' : '↓ 下滑'}加载更多 · 已显示 ${Math.min(shown, total)} / ${total} ${unit}`
}

export function appendEvents<T>(existing: T[], extra: T[], cap = EVENT_CAP) {
  return [...existing, ...extra].slice(-cap)
}

export function sessionTitle(current: string, text: string) {
  const next = text.trim().slice(0, 14)
  return current || next
}

export function formatSessionTime(at: number) {
  return new Date(at).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

const EVENT_KIND: Record<string, DebugEventKind> = {
  工具: 'tool',
  护栏: 'guardrail',
  模型: 'step',
  检索: 'step',
  智能体: 'step',
  事件: 'step',
}

export function traceEvent(row: StepRow): DebugEvent {
  const kind = EVENT_KIND[row.type] ?? 'step'
  const text = [row.name, row.output].filter(Boolean).join(' · ')
  return { kind, text, hit: kind === 'guardrail' && row.status !== 'ok' }
}

function isMessage(value: unknown): value is DebugMessage {
  if (!value || typeof value !== 'object') return false
  const row = value as DebugMessage
  return (row.role === 'user' || row.role === 'agent') && typeof row.text === 'string'
}

function isEvent(value: unknown): value is DebugEvent {
  if (!value || typeof value !== 'object') return false
  const row = value as DebugEvent
  return typeof row.kind === 'string' && typeof row.text === 'string'
}

function isSession(value: unknown): value is DebugSession {
  if (!value || typeof value !== 'object') return false
  const row = value as DebugSession
  return typeof row.id === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(row.id)
    && typeof row.title === 'string'
    && typeof row.at === 'number'
    && Array.isArray(row.messages) && row.messages.every(isMessage)
    && Array.isArray(row.events) && row.events.every(isEvent)
}

export function parseSessions(raw: string | null, now = Date.now()): DebugSession[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return byRecent(pruneSessions(parsed.filter(isSession), now))
  } catch {
    return []
  }
}
