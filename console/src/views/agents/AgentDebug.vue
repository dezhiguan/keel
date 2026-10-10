<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import { chatWithAgent, getAgent, type AgentDetail } from '@/api/agents'
import { toKeelError } from '@/api/http'
import { getTrace, listTraces, type TraceSummary } from '@/api/traces'
import { agentStatus, fmtMs, hms, nodeStatus } from '@/utils/format'
import { avatarColor, avatarLetter } from './agentDrawer'
import { MODE_LABEL, debugBlocked, debugMode, debugWarning, describeCron, stepRows, type StepRow } from './debug'

interface Call {
  id: number
  at: string
  input: string
  text?: string
  traceId?: string | null
  ms: number
  error?: string
}

interface Message {
  role: 'user' | 'agent'
  text: string
  traceId?: string | null
  error?: boolean
}

const RUNNER = {
  task: { title: '任务说明', placeholder: '描述要它完成的事，例如：WT-07 变桨系统报 F203，给出检修建议', button: '运行' },
  service: { title: '发送测试输入', placeholder: '模拟一次上游触发的输入，例如一段 PR 变更说明', button: '发送' },
  schedule: { title: '立即运行一次', placeholder: '本次运行的输入。手动运行不改变定时计划', button: '立即运行' },
} as const

const route = useRoute()
const router = useRouter()
const detail = ref<AgentDetail | null>(null)
const loading = ref(false)
const calls = ref<Call[]>([])
const busy = ref(false)

const messages = ref<Message[]>([])
const draft = ref('')
const thread = ref<HTMLElement | null>(null)

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
const envVersion = computed(() => [detail.value?.env, detail.value?.version].filter(Boolean).join(' · ') || '—')

let callSeq = 0
async function invoke(input: string): Promise<Call> {
  const started = performance.now()
  const call: Call = { id: ++callSeq, at: new Date().toISOString(), input, ms: 0 }
  busy.value = true
  try {
    // TODO(P2-18): /chat 每轮独立、服务端写死 env=dev；要多轮上下文和按环境调用需要接口带 sessionId、env。
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
  if (!text || busy.value) return
  draft.value = ''
  messages.value.push({ role: 'user', text })
  scrollThread()
  const call = await invoke(text)
  messages.value.push(call.error
    ? { role: 'agent', text: call.error, error: true }
    : { role: 'agent', text: call.text || '（空回复）', traceId: call.traceId })
  scrollThread()
}

function scrollThread() {
  nextTick(() => {
    if (thread.value) thread.value.scrollTop = thread.value.scrollHeight
  })
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

function clearChat() {
  messages.value = []
}

watch(name, async (agent) => {
  detail.value = null
  calls.value = []
  messages.value = []
  lastRun.value = null
  recent.value = []
  if (!agent) return
  loading.value = true
  try {
    const result = await getAgent(agent)
    if (agent !== name.value) return
    detail.value = result
  } catch (error) {
    ElMessage.error(`加载智能体失败：${toKeelError(error).message}`)
    return
  } finally {
    loading.value = false
  }
  if (!blocked.value && (mode.value === 'service' || mode.value === 'schedule')) loadRecent()
}, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="pv">
    <div class="vh">
      <button class="btn sm" type="button" @click="back">← 返回详情</button>
      <span class="av" :style="{ background: `${avatarColor(name)}22`, color: avatarColor(name) }">{{ avatarLetter(detail?.displayName || name) }}</span>
      <h2>{{ detail?.displayName || name }}</h2>
      <StatusPill v-if="detail" v-bind="agentStatus(detail.status)" />
      <span v-if="detail" class="pill nd p-acc" :title="MODE_LABEL[mode].hint">{{ MODE_LABEL[mode].name }}</span>
      <span class="sub mono">{{ name }} · {{ envVersion }}</span>
      <span class="sp" />
      <RouterLink class="btn ghost" to="/traces">全部链路 ↗</RouterLink>
    </div>

    <div v-if="detail && blocked" class="card"><p class="empty">{{ blocked }}</p></div>

    <template v-else-if="detail">
      <div class="banner info">调试是真实调用：消耗该智能体的日预算，写链路和审计；高风险工具照常走审批。</div>
      <div v-if="warning" class="banner warn">{{ warning }}</div>

      <div class="pv-grid">
        <div>
          <div v-if="mode === 'chat'" class="card pv-chat">
            <h3>对话 <small>每轮独立，暂不带上文</small><span class="sp" /><button class="btn sm ghost" type="button" :disabled="!messages.length" @click="clearChat">清空</button></h3>
            <div ref="thread" class="pv-thread">
              <p v-if="!messages.length" class="empty">发一句话试试它。Enter 发送，Shift+Enter 换行。</p>
              <div v-for="(msg, index) in messages" :key="index" class="pv-msg" :class="[msg.role, { err: msg.error }]">
                <div class="bubble">{{ msg.text }}</div>
                <RouterLink v-if="msg.traceId" class="pv-trace mono" :to="`/traces/${msg.traceId}`">链路 {{ msg.traceId }}</RouterLink>
              </div>
              <div v-if="busy" class="pv-msg agent"><div class="bubble mut">思考中…</div></div>
            </div>
            <div class="pv-composer">
              <textarea v-model="draft" class="inp" rows="2" placeholder="输入问题" @keydown.enter.exact.prevent="sendChat" />
              <button v-write class="btn pri" type="button" :disabled="busy || !draft.trim()" @click="sendChat">发送</button>
            </div>
          </div>

          <template v-else>
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
          </template>
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
.pv { min-height: 240px; }
.pv-grid { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 14px; align-items: start; }
.pv-chat { display: flex; flex-direction: column; height: calc(100vh - 250px); min-height: 420px; }
.pv-thread { flex: 1; overflow: auto; display: flex; flex-direction: column; gap: 12px; padding: 4px 2px 12px; }
.pv-msg { display: flex; flex-direction: column; max-width: 78%; }
.pv-msg.user { align-self: flex-end; align-items: flex-end; }
.pv-msg .bubble { white-space: pre-wrap; line-height: 1.6; font-size: 13px; padding: 8px 12px; border-radius: 10px; background: var(--panel2); border: 1px solid var(--line2); }
.pv-msg.user .bubble { background: rgba(255, 122, 69, 0.12); border-color: rgba(255, 122, 69, 0.35); color: var(--white); }
.pv-msg.err .bubble { color: var(--bad); border-color: #5a2a2e; background: #1c1216; }
.pv-trace { font-size: 11px; color: var(--soft); margin-top: 4px; text-decoration: none; }
.pv-composer { display: flex; gap: 8px; align-items: flex-end; border-top: 1px solid var(--line); padding-top: 12px; }
.pv-composer textarea { flex: 1; resize: none; }
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
@media (max-width: 1100px) { .pv-grid { grid-template-columns: 1fr; } .pv-side { position: static; } }
</style>
