<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import MessageMarkdown from '@/components/MessageMarkdown.vue'
import {
  chatWithAgent, createDebugSession, deleteDebugSession, getAgent, listDebugMessages, listDebugSessions,
  streamAgentChat, type AgentDetail, type DebugMessageRow, type DebugSessionSummary,
} from '@/api/agents'
import { toKeelError } from '@/api/http'
import { getTrace, listTraces, type TraceSummary } from '@/api/traces'
import { useUserStore } from '@/stores/user'
import { agentStatus, fmtMs, hms, nodeStatus } from '@/utils/format'
import {
  DEBUG_PAGE, MODE_LABEL, SSE_LABEL, appendEvents, debugBlocked, debugMode, debugWarning, describeCron,
  describeLiveFrame, formatSessionTime, pageStatus, sessionTitle, stepRows, visibleTail,
  type DebugEvent, type DebugMessage, type DebugSession, type SseState, type StepRow,
} from './debug'

interface Call {
  id: number
  at: string
  input: string
  text?: string
  traceId?: string | null
  ms: number
  error?: string
}

const RUNNER = {
  task: { title: '任务说明', placeholder: '描述要它完成的事，例如：WT-07 变桨系统报 F203，给出检修建议', button: '运行' },
  service: { title: '发送测试输入', placeholder: '模拟一次上游触发的输入，例如一段 PR 变更说明', button: '发送' },
  schedule: { title: '立即运行一次', placeholder: '本次运行的输入。手动运行不改变定时计划', button: '立即运行' },
} as const

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const detail = ref<AgentDetail | null>(null)
const loading = ref(false)
const calls = ref<Call[]>([])
const busy = ref(false)
const sessionBusy = ref(false)

const sessions = ref<DebugSession[]>([])
const currentId = ref<string | null>(null)
const shown = reactive<Record<keyof typeof DEBUG_PAGE, number>>({
  sessions: DEBUG_PAGE.sessions,
  messages: DEBUG_PAGE.messages,
  events: DEBUG_PAGE.events,
})
const paging = reactive({ sessions: false, messages: false, events: false })
const sse = ref<SseState>('idle')
const pendingEvents = ref<DebugEvent[]>([])
const liveText = ref<string | null>(null)
const canStop = ref(false)
const draft = ref('')
const sessionEl = ref<HTMLElement | null>(null)
const threadEl = ref<HTMLElement | null>(null)
const eventEl = ref<HTMLElement | null>(null)

const runInput = ref('')
const lastRun = ref<Call | null>(null)
const steps = ref<StepRow[]>([])
const stepState = ref<'idle' | 'loading' | 'pending' | 'ok'>('idle')

const recent = ref<TraceSummary[]>([])
const recentLoading = ref(false)

const name = computed(() => String(route.params.name ?? ''))
const mode = computed(() => debugMode(detail.value))
const blocked = computed(() => (detail.value ? debugBlocked(detail.value.status) : null))
const warning = computed(() => (detail.value ? debugWarning(detail.value.status) : null))
const runner = computed(() => (mode.value === 'chat' ? null : RUNNER[mode.value]))
const cronText = computed(() => describeCron(detail.value?.interaction?.schedule))
const envVersion = computed(() => [detail.value?.env, detail.value?.version, MODE_LABEL[mode.value].name].filter(Boolean).join(' · ') || '—')
const current = computed(() => sessions.value.find((item) => item.id === currentId.value) ?? null)
const combinedEvents = computed(() => [...(current.value?.events ?? []), ...pendingEvents.value])
const visibleEvents = computed(() => visibleTail(combinedEvents.value, shown.events))
const sseLabel = computed(() => SSE_LABEL[sse.value])
const sessionTotal = ref(0)
const sessionCursor = ref<string | null>(null)

let callSeq = 0
let abort: AbortController | null = null
let alive = true
const pageTimers = { sessions: 0, messages: 0, events: 0 }

function asSession(row: DebugSessionSummary): DebugSession {
  return {
    id: row.id,
    title: row.title || '',
    messages: [],
    events: [],
    at: Date.parse(row.updatedAt) || Date.now(),
    messageCount: row.messageCount ?? 0,
  }
}

function asMessage(row: DebugMessageRow): DebugMessage {
  return { role: row.role, text: row.text, traceId: row.traceId }
}

async function loadMessages(session: DebugSession) {
  const page = await listDebugMessages(name.value, session.id)
  session.messages = (page.items ?? []).map(asMessage)
  session.messageCount = Math.max(session.messageCount, session.messages.length)
  session.older = page.nextCursor ?? null
}

async function loadSessions() {
  sessionBusy.value = true
  shown.sessions = DEBUG_PAGE.sessions
  shown.messages = DEBUG_PAGE.messages
  shown.events = DEBUG_PAGE.events
  sse.value = 'idle'
  pendingEvents.value = []
  liveText.value = null
  try {
    const page = await listDebugSessions(name.value)
    if (!alive) return
    let items = page.items ?? []
    sessionTotal.value = page.total ?? items.length
    sessionCursor.value = page.nextCursor ?? null
    if (!items.length && !userStore.readOnly) {
      const created = await createDebugSession(name.value)
      if (!alive) return
      items = [created]
      sessionTotal.value = 1
      sessionCursor.value = null
    }
    sessions.value = items.map(asSession)
    currentId.value = sessions.value[0]?.id ?? null
    if (current.value) await loadMessages(current.value)
  } catch (error) {
    if (!alive) return
    sessions.value = []
    currentId.value = null
    if (!userStore.readOnly) ElMessage.error(`加载会话失败：${toKeelError(error).message}`)
  } finally {
    sessionBusy.value = false
  }
  scrollEnd(threadEl.value)
  scrollEnd(eventEl.value)
}

function scrollEnd(el: HTMLElement | null) {
  nextTick(() => {
    if (el) el.scrollTop = el.scrollHeight
  })
}

function loadMore(which: 'sessions' | 'messages' | 'events', total: number, el: HTMLElement | null) {
  if (!el || paging[which] || shown[which] >= total) return
  paging[which] = true
  const height = el.scrollHeight
  const top = el.scrollTop
  window.clearTimeout(pageTimers[which])
  pageTimers[which] = window.setTimeout(() => {
    shown[which] += DEBUG_PAGE[which]
    paging[which] = false
    if (which === 'sessions') return
    nextTick(() => {
      el.scrollTop = el.scrollHeight - height + top
    })
  }, 350)
}

async function onSessionScroll() {
  const el = sessionEl.value
  if (!el || paging.sessions || !sessionCursor.value) return
  if (el.scrollTop + el.clientHeight < el.scrollHeight - 8) return
  paging.sessions = true
  try {
    const page = await listDebugSessions(name.value, sessionCursor.value)
    const more = (page.items ?? []).map(asSession)
    sessions.value = [...sessions.value, ...more]
    sessionCursor.value = page.nextCursor ?? null
    if (page.total != null) sessionTotal.value = page.total
  } catch (error) {
    ElMessage.error(`加载会话失败：${toKeelError(error).message}`)
  } finally {
    paging.sessions = false
  }
}

async function onThreadScroll() {
  const el = threadEl.value
  const session = current.value
  if (!el || !session || paging.messages || session.older == null || el.scrollTop > 8) return
  paging.messages = true
  const height = el.scrollHeight
  const top = el.scrollTop
  try {
    const page = await listDebugMessages(name.value, session.id, session.older)
    const older = (page.items ?? []).map(asMessage)
    session.messages = [...older, ...session.messages]
    session.older = page.nextCursor ?? null
  } catch (error) {
    ElMessage.error(`加载消息失败：${toKeelError(error).message}`)
  } finally {
    paging.messages = false
    nextTick(() => {
      el.scrollTop = el.scrollHeight - height + top
    })
  }
}

function onEventScroll() {
  const el = eventEl.value
  if (el && el.scrollTop <= 8) loadMore('events', combinedEvents.value.length, el)
}

async function invoke(input: string): Promise<Call> {
  const started = performance.now()
  const call: Call = { id: ++callSeq, at: new Date().toISOString(), input, ms: 0 }
  busy.value = true
  try {
    const answer = await chatWithAgent(name.value, input)
    call.text = answer.text ?? ''
    call.traceId = answer.traceId ?? null
  } catch (error) {
    call.error = toKeelError(error).message
  } finally {
    call.ms = Math.round(performance.now() - started)
    busy.value = false
  }
  calls.value = [call, ...calls.value]
  return call
}

async function sendChat() {
  const text = draft.value.trim()
  const session = current.value
  if (!text || canStop.value || busy.value || sessionBusy.value || !session) return
  draft.value = ''
  session.messages.push({ role: 'user', text })
  session.title = sessionTitle(session.title, text)
  session.at = Date.now()
  sessions.value = [session, ...sessions.value.filter((item) => item.id !== session.id)]
  shown.events = DEBUG_PAGE.events
  pendingEvents.value = []
  liveText.value = ''
  sse.value = 'streaming'
  canStop.value = true
  scrollEnd(threadEl.value)
  scrollEnd(eventEl.value)

  abort = new AbortController()
  const started = performance.now()
  let answer = ''
  let traceId: string | null = null
  let failed = ''
  let blockedCall = false
  let suspended = false
  try {
    await streamAgentChat(name.value, text, session.id, abort.signal, (frame) => {
      const live = describeLiveFrame(frame.event, frame.data)
      if (!live) return
      if (live.token) {
        liveText.value = `${liveText.value ?? ''}${live.token}`
        scrollEnd(threadEl.value)
      }
      if (frame.event === 'suspend') suspended = true
      if (live.answer) answer = live.answer
      if (live.traceId) traceId = live.traceId
      if (live.kind === 'error') {
        failed = live.text
        blockedCall = !!live.blocked
      }
      if (live.kind !== 'final') pendingEvents.value = [...pendingEvents.value, { kind: live.kind, text: live.text, hit: live.hit }]
      scrollEnd(eventEl.value)
    })
    if (!alive) return
    const ms = Math.round(performance.now() - started)
    if (failed) {
      session.messages.push({ role: 'agent', text: failed, error: true, meta: blockedCall ? '护栏已拦截' : undefined })
      sse.value = blockedCall ? 'blocked' : 'failed'
    } else if (suspended) {
      session.messages.push({ role: 'agent', text: answer || '等待继续', traceId, meta: '等待人工处理' })
      sse.value = 'suspended'
    } else if (answer || traceId) {
      const textAnswer = answer.trim() || liveText.value || '（空回复）'
      session.messages.push({ role: 'agent', text: textAnswer, traceId, meta: fmtMs(ms) })
      session.messageCount += 2
      pendingEvents.value = [...pendingEvents.value, { kind: 'final', text: traceId ? `${fmtMs(ms)} · ${traceId}` : fmtMs(ms) }]
      sse.value = 'done'
    } else {
      session.messages.push({ role: 'agent', text: liveText.value || '没有收到完整回复', error: true })
      sse.value = 'failed'
    }
    session.events = appendEvents(session.events, pendingEvents.value)
    session.at = Date.now()
  } catch (error) {
    if (!alive) return
    const stopped = abort?.signal.aborted
    if (stopped) {
      const taskAwareStop = name.value === 'keel-assistant' && detail.value?.env === 'staging'
      session.messages.push({ role: 'agent', text: taskAwareStop ? '已停止接收本次回复；后台执行未确认停止。' : '（已停止）', meta: taskAwareStop ? '接收已停止' : '已停止' })
      session.events = appendEvents(session.events, [...pendingEvents.value, { kind: 'error', text: taskAwareStop ? '已停止接收。若已创建任务，请通过对话查询或取消。' : '已停止，这次调用已取消' }])
      sse.value = 'stopped'
    } else {
      const message = toKeelError(error).message
      const blockedError = message.includes('GUARD_')
      session.messages.push({ role: 'agent', text: message, error: true, meta: blockedError ? '护栏已拦截' : undefined })
      session.events = appendEvents(session.events, [...pendingEvents.value, { kind: 'error', text: message }])
      sse.value = blockedError ? 'blocked' : 'failed'
    }
    session.at = Date.now()
  } finally {
    if (!alive) return
    pendingEvents.value = []
    liveText.value = null
    canStop.value = false
    abort = null
    scrollEnd(threadEl.value)
    scrollEnd(eventEl.value)
  }
}

function stop() {
  if (canStop.value) abort?.abort()
}

async function pick(id: string) {
  if (canStop.value || sessionBusy.value || id === currentId.value) return
  currentId.value = id
  shown.events = DEBUG_PAGE.events
  sse.value = 'idle'
  pendingEvents.value = []
  const session = current.value
  if (session && !session.messages.length && session.messageCount) {
    sessionBusy.value = true
    try {
      await loadMessages(session)
    } catch (error) {
      ElMessage.error(`加载消息失败：${toKeelError(error).message}`)
    } finally {
      sessionBusy.value = false
    }
  }
  scrollEnd(threadEl.value)
  scrollEnd(eventEl.value)
}

async function newSession() {
  if (canStop.value || sessionBusy.value) return
  sessionBusy.value = true
  try {
    const created = asSession(await createDebugSession(name.value))
    if (!alive) return
    sessions.value = [created, ...sessions.value]
    sessionTotal.value += 1
    currentId.value = created.id
    shown.events = DEBUG_PAGE.events
    sse.value = 'idle'
    pendingEvents.value = []
    nextTick(() => {
      if (sessionEl.value) sessionEl.value.scrollTop = 0
    })
  } catch (error) {
    ElMessage.error(`新建会话失败：${toKeelError(error).message}`)
  } finally {
    sessionBusy.value = false
  }
}

async function delSession() {
  if (canStop.value || sessionBusy.value || !currentId.value) return
  sessionBusy.value = true
  const id = currentId.value
  try {
    await deleteDebugSession(name.value, id)
    sessions.value = sessions.value.filter((item) => item.id !== id)
    sessionTotal.value = Math.max(0, sessionTotal.value - 1)
    currentId.value = sessions.value[0]?.id ?? null
    if (current.value && !current.value.messages.length) {
      await loadMessages(current.value).catch((error) => ElMessage.error(`加载消息失败：${toKeelError(error).message}`))
    }
    shown.events = DEBUG_PAGE.events
    sse.value = 'idle'
    pendingEvents.value = []
    ElMessage.warning('会话已删除')
  } catch (error) {
    ElMessage.error(`删除会话失败：${toKeelError(error).message}`)
  } finally {
    sessionBusy.value = false
  }
}

async function run() {
  const text = runInput.value.trim()
  if (!text || busy.value) return
  steps.value = []
  stepState.value = 'idle'
  lastRun.value = null
  const call = await invoke(text)
  lastRun.value = call
  if (call.traceId) loadSteps(call.traceId)
  if (mode.value !== 'task') loadRecent()
}

async function loadSteps(traceId: string) {
  stepState.value = 'loading'
  try {
    const trace = await getTrace(traceId)
    if (lastRun.value?.traceId !== traceId) return
    steps.value = stepRows(trace.nodes ?? [])
    stepState.value = 'ok'
  } catch {
    if (lastRun.value?.traceId === traceId) stepState.value = 'pending'
  }
}

async function loadRecent() {
  const env = detail.value?.env
  if (!env) return
  recentLoading.value = true
  try {
    const to = new Date()
    const from = new Date(to.getTime() - 7 * 86_400_000)
    const page = await listTraces({ env, agent: name.value, from: from.toISOString(), to: to.toISOString(), page: 1, size: 10 })
    recent.value = page.items ?? []
  } catch (error) {
    ElMessage.error(`加载最近运行失败：${toKeelError(error).message}`)
  } finally {
    recentLoading.value = false
  }
}

function back() {
  router.push({ path: '/agents', query: { drawer: name.value } })
}

onUnmounted(() => {
  alive = false
  window.clearTimeout(pageTimers.sessions)
  window.clearTimeout(pageTimers.messages)
  window.clearTimeout(pageTimers.events)
  abort?.abort()
})

watch(name, async (agent) => {
  detail.value = null
  calls.value = []
  sessions.value = []
  currentId.value = null
  lastRun.value = null
  recent.value = []
  if (!agent) return
  loading.value = true
  try {
    const result = await getAgent(agent, false)
    if (agent !== name.value) return
    detail.value = result
  } catch (error) {
    ElMessage.error(`加载智能体失败：${toKeelError(error).message}`)
    return
  } finally {
    loading.value = false
  }
  if (blocked.value) return
  if (mode.value === 'chat') loadSessions()
  else if (mode.value === 'service' || mode.value === 'schedule') loadRecent()
  const incoming = route.query.draft
  if (typeof incoming !== 'string' || !incoming) return
  if (mode.value === 'chat') draft.value = incoming
  else runInput.value = incoming
  const query = { ...route.query }
  delete query.draft
  router.replace({ path: route.path, query })
  ElMessage.info('已带入输入，按回车发送，看右侧 guardrail 事件')
}, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="pv" :class="{ fill: mode === 'chat' && !blocked }">
    <div class="vh">
      <button class="btn sm ghost" type="button" @click="back">← 返回</button>
      <h2>{{ name }} · 调试</h2>
      <StatusPill v-if="detail" v-bind="agentStatus(detail.status)" />
      <span v-if="detail" class="sub">{{ envVersion }}</span>
      <span class="sp" />
      <RouterLink class="btn ghost" to="/traces">全部链路 ↗</RouterLink>
    </div>

    <div v-if="detail && blocked" class="card"><p class="empty">{{ blocked }}</p></div>

    <template v-else-if="detail && mode === 'chat'">
      <div v-if="warning" class="banner warn">{{ warning }}</div>

      <div class="dbg">
        <div class="card">
          <h3>会话 <small>{{ sessionBusy ? '正在切换…' : `${sessions.length} 个 · 仅自己可见` }}</small></h3>
          <button v-write class="btn pri new-session" type="button" :disabled="canStop || sessionBusy" @click="newSession">+ 新会话</button>
          <div ref="sessionEl" class="slist vlist" @scroll="onSessionScroll">
            <div
              v-for="item in sessions"
              :key="item.id"
              class="vi"
              :class="{ on: item.id === currentId, locked: canStop || sessionBusy }"
              :aria-disabled="canStop || sessionBusy"
              @click="pick(item.id)"
            >
              <b>{{ item.title || '新会话' }}</b>
              <div class="m">{{ item.messageCount }} 条 · {{ formatSessionTime(item.at) }}</div>
            </div>
            <div class="pg" :class="{ loading: paging.sessions }">{{ pageStatus(sessions.length, sessionTotal, '个', 'down', paging.sessions) }}</div>
          </div>
          <button v-write class="btn sm danger drop" type="button" :disabled="canStop || sessionBusy || !current" @click="delSession">删除当前会话</button>
        </div>

        <div class="card">
          <div class="store">
            <span>会话 <b class="mono">{{ current?.id }}</b></span>
            <span>存储 <b>keel-server PG · session_message</b></span>
            <span>保留 <b>7 天</b></span>
          </div>
          <div ref="threadEl" class="thread" @scroll="onThreadScroll">
            <div v-if="!current" class="mut empty-thread">还没有会话。点「新会话」开始。</div>
            <div v-else-if="!current.messages.length" class="mut empty-thread">新会话。历史保存在 keel-server，刷新后还能读回。</div>
            <template v-else>
              <div class="pg" :class="{ loading: paging.messages }">{{ pageStatus(current.messages.length, current.messageCount, '条', 'up', paging.messages) }}</div>
              <div v-for="(msg, index) in current.messages" :key="index" class="msg" :class="{ u: msg.role === 'user' }">
                <div>
                  <div class="bub" :class="{ err: msg.error }">
                    <MessageMarkdown v-if="msg.role === 'agent' && !msg.error" :text="msg.text" />
                    <template v-else>{{ msg.text }}</template>
                  </div>
                  <div v-if="msg.meta || msg.traceId" class="meta">
                    <span v-if="msg.meta">{{ msg.meta }}</span>
                    <RouterLink v-if="msg.traceId" :to="`/traces/${msg.traceId}`">查看链路</RouterLink>
                  </div>
                </div>
              </div>
              <div v-if="liveText !== null" class="msg">
                <div><div class="bub cursor"><MessageMarkdown :text="liveText" /></div></div>
              </div>
            </template>
          </div>
          <div class="compose">
            <input v-model="draft" class="inp" placeholder="输入消息，回车发送" :disabled="sessionBusy" @keydown.enter.prevent="sendChat">
            <button v-write class="btn pri" type="button" :disabled="canStop || sessionBusy || !draft.trim()" @click="sendChat">发送</button>
            <button class="btn" type="button" :disabled="!canStop" @click="stop">停止</button>
          </div>
          <div class="hint">三栏都是滚动分页：会话列表滚到底加载下一页，对话和事件滚到顶加载更早的（接口按游标分页，每页会话 10 个、消息 10 条、事件 20 条）。调试是真实调用，消耗日预算，并写入链路和审计。</div>
        </div>

        <div class="card">
          <h3>实时事件 <span class="pill nd" :class="sseLabel.cls">{{ sseLabel.text }}</span><small v-if="combinedEvents.length">{{ combinedEvents.length }} 条</small></h3>
          <div ref="eventEl" class="events" @scroll="onEventScroll">
            <div v-if="!combinedEvents.length" class="mut ev-empty">发送消息后这里实时显示 SSE 事件。</div>
            <template v-else>
              <div class="pg" :class="{ loading: paging.events }">{{ pageStatus(shown.events, combinedEvents.length, '条', 'up', paging.events) }}</div>
              <div v-for="(event, index) in visibleEvents" :key="index" class="ev" :class="[event.kind, { hit: event.hit }]">
                <div class="k">event: {{ event.kind }}</div>
                {{ event.text }}
              </div>
            </template>
          </div>
        </div>
      </div>
    </template>

    <template v-else-if="detail">
      <div class="banner info">调试是真实调用：消耗该智能体的日预算，写链路和审计；高风险工具照常走审批。</div>
      <div v-if="warning" class="banner warn">{{ warning }}</div>

      <div class="pv-grid">
        <div>
          <div v-if="mode === 'service'" class="card">
            <h3>触发方式</h3>
            <div class="kv">
              <span>触发方</span>
              <b v-if="detail.interaction?.trigger">{{ detail.interaction.trigger }}</b>
              <span v-else class="mut">agent.yaml 没有声明 interaction.trigger</span>
              <span>用户入口</span><b>无，平时由上游调用；这里可以发一次测试输入</b>
            </div>
          </div>

          <div v-if="mode === 'schedule'" class="card">
            <h3>运行计划</h3>
            <div class="kv">
              <span>cron</span><b class="mono">{{ detail.interaction?.schedule || '—' }}</b>
              <span>含义</span><b>{{ cronText || '—' }}</b>
              <span>时区</span><b>{{ detail.interaction?.timezone || 'Asia/Shanghai' }}</b>
            </div>
          </div>

          <div v-if="runner" class="card">
            <h3>{{ runner.title }}</h3>
            <textarea v-model="runInput" class="inp pv-input" rows="4" :placeholder="runner.placeholder" />
            <div class="pv-actions">
              <span class="sp" />
              <button v-write class="btn pri" type="button" :disabled="busy || !runInput.trim()" @click="run">{{ busy ? '运行中…' : runner.button }}</button>
            </div>
          </div>

          <div v-if="lastRun" class="card">
            <h3>
              执行结果
              <span class="pill" :class="lastRun.error ? 'p-bad' : 'p-ok'">{{ lastRun.error ? '失败' : '完成' }}</span>
              <small>{{ fmtMs(lastRun.ms) }}</small>
              <span class="sp" />
              <RouterLink v-if="lastRun.traceId" class="btn sm ghost" :to="`/traces/${lastRun.traceId}`">查看链路 ↗</RouterLink>
            </h3>
            <pre class="pv-output" :class="{ err: lastRun.error }">{{ lastRun.error || lastRun.text || '（空输出）' }}</pre>
            <h4 class="pv-sub">执行步骤</h4>
            <p v-if="!lastRun.traceId" class="mut">这次调用没有返回 traceId，无法读取步骤。</p>
            <p v-else-if="stepState === 'loading'" class="mut">读取链路中…</p>
            <p v-else-if="stepState === 'pending'" class="mut">链路还在写入 Langfuse。<a href="javascript:;" @click="loadSteps(lastRun.traceId!)">刷新</a></p>
            <table v-else-if="steps.length" class="t">
              <thead><tr><th>步骤</th><th>类型</th><th>耗时</th><th>状态</th></tr></thead>
              <tbody>
                <tr v-for="row in steps" :key="row.id">
                  <td :style="{ paddingLeft: `${8 + row.depth * 16}px` }">{{ row.name }}<small v-if="row.output" class="pv-out mut">{{ row.output }}</small></td>
                  <td>{{ row.type }}</td>
                  <td class="mono">{{ fmtMs(row.durationMs) }}</td>
                  <td><StatusPill v-bind="nodeStatus(row.status)" /></td>
                </tr>
              </tbody>
            </table>
            <p v-else class="mut">链路里没有子步骤。</p>
          </div>

          <div v-if="mode !== 'task'" class="card">
            <h3>最近运行 <small>近 7 天 · {{ detail.env }}</small><span class="sp" /><button class="btn sm ghost" type="button" @click="loadRecent">刷新</button></h3>
            <table v-loading="recentLoading" class="t">
              <thead><tr><th>时间</th><th>输入</th><th>耗时</th><th>状态</th></tr></thead>
              <tbody>
                <tr v-if="!recent.length"><td colspan="4" class="empty">近 7 天没有运行记录</td></tr>
                <tr v-for="row in recent" :key="row.traceId" class="click" @click="router.push(`/traces/${row.traceId}`)">
                  <td class="mono">{{ hms(row.startedAt) }}</td>
                  <td class="pv-q">{{ row.question || '—' }}</td>
                  <td class="mono">{{ fmtMs(row.durationMs) }}</td>
                  <td><StatusPill v-bind="nodeStatus(row.status)" /></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <aside class="card pv-side">
          <h3>本页调用 <small>{{ calls.length }} 次</small></h3>
          <p v-if="!calls.length" class="mut">还没有调用。离开页面后这里清空，记录仍在链路追踪里。</p>
          <ul v-else class="pv-calls">
            <li v-for="call in calls" :key="call.id">
              <div class="row"><span class="mono mut">{{ hms(call.at) }}</span><span class="pill" :class="call.error ? 'p-bad' : 'p-ok'">{{ call.error ? '失败' : 'ok' }}</span><span class="mono mut">{{ fmtMs(call.ms) }}</span></div>
              <div class="q">{{ call.input }}</div>
              <RouterLink v-if="call.traceId" class="mono" :to="`/traces/${call.traceId}`">{{ call.traceId }}</RouterLink>
            </li>
          </ul>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.pv { min-height: 0; }
.pv.fill {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  max-height: 100%;
  overflow: hidden;
}
.pv .vh { margin-bottom: 14px; flex-shrink: 0; }
/* Fill the main pane so the composer and delete button stay inside the columns. */
.dbg {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr) 270px;
  grid-template-rows: minmax(0, 1fr);
  gap: 14px;
  overflow: hidden;
}
.dbg .card {
  box-sizing: border-box;
  margin: 0;
  height: 100%;
  max-height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.dbg .card > h3,
.store,
.compose,
.hint,
.new-session,
.drop { flex-shrink: 0; }
.new-session { margin-bottom: 10px; align-self: flex-start; }
.drop { margin-top: 8px; align-self: flex-start; }
.slist { overflow: auto; flex: 1; min-height: 0; }
.slist .vi .m { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.slist .vi.locked { pointer-events: none; opacity: 0.65; }
.thread, .events { flex: 1; overflow: auto; min-height: 0; }
.thread { padding: 4px 2px; }
.pg { font-size: 11.5px; color: var(--mute); text-align: center; padding: 6px 0; }
.pg.loading { color: var(--acc); }
.msg { display: flex; margin: 8px 0; }
.msg.u { justify-content: flex-end; }
.msg > div { min-width: 0; max-width: 88%; }
.msg.u > div { max-width: 78%; }
.msg.u > div { text-align: right; }
.bub {
  box-sizing: border-box;
  max-width: 100%;
  overflow-wrap: anywhere;
  display: inline-block;
  text-align: left;
  padding: 8px 12px;
  border-radius: 10px;
  background: var(--panel2);
  border: 1px solid var(--line);
  white-space: pre-wrap;
}
.msg.u .bub { background: #2a1a12; border-color: #5a3220; color: var(--white); }
.bub.err { color: var(--bad); border-color: #5a2a2e; background: #1c1216; }
.meta { font-size: 11px; color: var(--mute); margin-top: 3px; display: flex; gap: 8px; }
.msg.u .meta { justify-content: flex-end; }
.meta a { color: var(--soft); text-decoration: none; }
.compose { display: flex; gap: 8px; margin-top: 10px; }
.compose .inp { flex: 1; min-width: 0; }
.hint { font-size: 11.5px; color: var(--mute); margin-top: 6px; line-height: 1.45; }
.store { font-size: 11.5px; color: var(--mute); display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.store b { color: var(--text); font-weight: 500; }
.empty-thread, .ev-empty { text-align: center; padding: 40px 0; font-size: 12.5px; }
.ev-empty { text-align: left; padding: 0; font-size: 12px; }
.ev { font-size: 12px; border-left: 2px solid var(--line2); padding: 2px 0 6px 10px; margin-left: 4px; }
.ev .k { font-family: var(--mono); font-size: 11px; color: var(--soft); }
.ev.guardrail .k { color: var(--violet); }
.ev.guardrail.hit { border-left-color: var(--violet); }
.ev.step .k { color: var(--acc); }
.ev.final .k { color: var(--ok); }
.ev.error .k { color: var(--bad); }
.bub.cursor::after { content: "▍"; color: var(--acc); animation: blink 1s infinite; }
@keyframes blink { 50% { opacity: 0; } }
.pv-grid { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 14px; align-items: start; }
.pv-input { width: 100%; box-sizing: border-box; resize: vertical; }
.pv-actions { display: flex; margin-top: 10px; }
.pv-output { white-space: pre-wrap; background: #0a0f18; border: 1px solid var(--line); border-radius: 9px; padding: 12px 14px; line-height: 1.65; color: #c9d4e3; margin: 0; font-family: inherit; font-size: 13px; }
.pv-output.err { color: var(--bad); }
.pv-sub { margin: 16px 0 8px; color: var(--white); font-size: 13px; font-weight: 600; }
.pv-out { display: block; font-size: 11.5px; margin-top: 2px; }
.pv-q { max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pv-side { position: sticky; top: 0; }
.pv-calls { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; max-height: calc(100vh - 280px); overflow: auto; }
.pv-calls li { border-bottom: 1px solid var(--line); padding-bottom: 10px; font-size: 12.5px; }
.pv-calls .row { display: flex; gap: 8px; align-items: center; }
.pv-calls .q { margin: 4px 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pv-calls a { color: var(--soft); font-size: 11.5px; text-decoration: none; }
@media (max-width: 1100px) {
  .pv.fill { height: auto; max-height: none; overflow: visible; }
  .dbg { grid-template-columns: 1fr; height: auto; overflow: visible; }
  .dbg .card { height: 520px; min-height: 0; }
  .slist, .thread, .events { max-height: none; }
  .pv-grid { grid-template-columns: 1fr; }
  .pv-side { position: static; }
}
</style>
