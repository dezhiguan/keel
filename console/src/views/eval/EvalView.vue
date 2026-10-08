<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import { getEvalRun, getLatestEval, runEval, type EvalResult } from '@/api/eval'
import { listSuspendedRuns, openApproval } from '@/api/approvals'
import { listAgents } from '@/api/agents'
import { toKeelError } from '@/api/http'
import { useApprovalsStore } from '@/stores/approvals'
import { useEnvStore } from '@/stores/env'
import { useUserStore } from '@/stores/user'
import type { StatusTone } from '@/utils/format'
import { reviewPath } from '@/views/tools/approvalGroups'
import { deltaText, expectedSubject, formatScore, gateRule, holdoutGap, holdoutGapAlarm, holdoutGapText } from './evalCopy'

const VERDICT: Record<string, { label: string; tone: StatusTone }> = {
  IMPROVED: { label: '提升', tone: 'ok' },
  TOLERATED: { label: '容忍', tone: 'ok' },
  EXCEEDED: { label: '超出阈值', tone: 'failed' },
}

const userStore = useUserStore()
const envStore = useEnvStore()
const approvalsStore = useApprovalsStore()

const agent = ref('')
const agentOptions = ref<string[]>([])
const result = ref<EvalResult | null>(null)
const pending = ref<{ jobId: string; agent: string }[]>([])
const gap = computed(() => holdoutGap(result.value?.scoreTotal, result.value?.holdout?.score))
const loading = ref(false)
const progress = ref<number | null>(null)
const expecting = ref(false)
const expectReason = ref('')
const expectInvalid = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
let polling = false
let loadSeq = 0

async function load() {
  const seq = ++loadSeq
  const name = agent.value
  loading.value = true
  try {
    const next = await getLatestEval(name)
    if (seq !== loadSeq) return
    result.value = next
  } catch (error) {
    if (seq !== loadSeq) return
    result.value = null
    const keel = toKeelError(error)
    if (keel.code !== 'SERVER_NOT_FOUND') ElMessage.error(`加载评测结果失败：${keel.message}`)
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function run() {
  if (!agent.value) return
  try {
    const { runId } = await runEval(agent.value)
    progress.value = 0
    clearInterval(timer)
    timer = setInterval(async () => {
      if (polling) return
      polling = true
      try {
        const r = await getEvalRun(runId!)
        progress.value = r.progress ?? 0
        if (r.state === 'RUNNING') return
        clearInterval(timer)
        progress.value = null
        if (r.result) result.value = r.result
        if (r.state === 'FAILED') ElMessage.warning('还没有可展示的评测记录')
        else ElMessage[r.result?.passed ? 'success' : 'warning'](`回归完成：总分 ${r.result?.scoreTotal ?? '—'}`)
      } finally {
        polling = false
      }
    }, 400)
  } catch (error) {
    ElMessage.error(`运行回归失败：${toKeelError(error).message}`)
  }
}

function markExpected() {
  expectReason.value = ''
  expectInvalid.value = false
  expecting.value = true
}

function closeExpect() {
  expecting.value = false
}

async function submitExpected() {
  const reason = expectReason.value.trim()
  if (reason.length < 10) {
    expectInvalid.value = true
    ElMessage.error('原因至少 10 个字')
    return
  }
  try {
    await openApproval({
      subjectType: 'agent.config',
      subjectRef: expectedSubject(agent.value, result.value),
      summary: reason,
      actorUser: userStore.user?.displayName || userStore.user?.userId || 'amy',
      agent: agent.value,
      risk: 'HIGH',
      env: envStore.env === 'all' ? 'prod' : envStore.env,
    })
    expecting.value = false
    ElMessage.warning('已提交，等待平台管理员审批')
    approvalsStore.refresh(envStore.env).catch(() => undefined)
  } catch (error) {
    ElMessage.error(`提交失败：${toKeelError(error).message}`)
  }
}

onMounted(async () => {
  await loadAgents()
  await loadPending()
})
watch(() => envStore.env, () => {
  loadAgents()
  loadPending()
})

async function loadPending() {
  try {
    const page = await listSuspendedRuns({ size: 100, env: envStore.env })
    pending.value = (page.items ?? [])
      .filter((run) => run.devflowGate === 'H2' && run.devflowJobId)
      .map((run) => ({ jobId: run.devflowJobId as string, agent: run.agent ?? '' }))
  } catch {
    pending.value = []
  }
}

async function loadAgents() {
  try {
    const page = await listAgents({ env: envStore.env, size: 100 })
    const names = (page.items ?? []).filter((a) => a.status !== 'DRAFT' && a.name).map((a) => a.name!)
    agentOptions.value = names
    if (!names.includes(agent.value)) {
      agent.value = names[0] ?? ''
      if (!agent.value) result.value = null
    }
  } catch (error) {
    ElMessage.error(`加载智能体失败：${toKeelError(error).message}`)
  }
}
onBeforeUnmount(() => clearInterval(timer))
watch(agent, () => {
  if (agent.value) load()
})
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>评测中心</h2>
      <span class="sub">Langfuse 数据集 {{ result?.dataset ?? (agent || '—') }} · 门禁由 keel-gate 判定</span>
      <span class="sp" />
      <select v-model="agent" class="inp">
        <option v-for="a in agentOptions" :key="a" :value="a">{{ a }}</option>
      </select>
      <button v-write class="btn pri" :disabled="!agent || progress !== null" @click="run">▶ 运行回归</button>
    </div>

    <div class="card">
      <h3>待评测确认<small>和审批中心是同一批待办</small></h3>
      <template v-if="pending.length">
        <RouterLink v-for="item in pending" :key="item.jobId" class="btn sm" :to="reviewPath(item.jobId)">{{ item.jobId }} {{ item.agent }} · 去确认</RouterLink>
      </template>
      <span v-else class="mut">无</span>
    </div>

    <template v-if="result">
      <div class="gate" :class="{ pass: result.passed }">
        <div class="big">{{ formatScore(result.scoreTotal) }}</div>
        <div>
          <b>{{ result.passed ? '门禁通过，可以发布' : '门禁未通过，发布到 prod 已被阻止' }}</b><br />
          <span class="mut">{{ gateRule(result) }}</span><br />
          <span class="mut">
            隐藏考题 {{ result.holdout ? formatScore(result.holdout.score) : '—' }}
            · 分差 <span :class="{ down: holdoutGapAlarm(gap) }">{{ holdoutGapText(gap) }}</span>
            <span v-if="holdoutGapAlarm(gap)" class="down">疑似针对可见用例特判</span>
            <span v-if="!result.holdout">人工编写的智能体没有隐藏考题</span>
            <RouterLink v-if="result.devflowJobId" :to="`/jobs/${result.devflowJobId}`">来源 {{ result.devflowJobId }}</RouterLink>
          </span>
        </div>
        <span class="sp" />
        <button v-if="!result.passed" class="btn" type="button" @click="markExpected">标记为预期变化</button>
      </div>
      <div v-if="progress !== null" class="prog"><i :style="{ width: `${progress * 100}%` }" /></div>

      <div class="card">
        <h3>分维度对比<small>prod 当前版本 vs 候选版本</small></h3>
        <table class="t">
          <thead><tr><th>维度（用例标签）</th><th>用例数</th><th>prod</th><th>候选</th><th>变化</th><th>判定</th></tr></thead>
          <tbody>
            <tr v-for="d in result.dimensions ?? []" :key="d.tag">
              <td>{{ d.tag }}</td>
              <td class="mono">{{ d.cases ?? '—' }}</td>
              <td class="mono">{{ formatScore(d.prodScore) }}</td>
              <td class="mono">{{ formatScore(d.candidateScore) }}</td>
              <td class="mono" :class="(d.deltaPt ?? 0) < 0 ? 'down' : 'up'">{{ deltaText(d.deltaPt) }}</td>
              <td><StatusPill v-bind="VERDICT[d.verdict ?? 'TOLERATED']" /></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <template v-else>
      <div v-if="progress !== null" class="prog"><i :style="{ width: `${progress * 100}%` }" /></div>
      <div v-if="!loading" class="card empty">{{ agent ? '该智能体还没有评测记录' : '还没有可评测的智能体' }}</div>
    </template>

    <Teleport to="body">
      <template v-if="expecting">
        <div class="mask on" @click="closeExpect" />
        <div class="modal on" role="dialog" aria-label="标记为预期变化">
          <div class="mh">标记为预期变化</div>
          <div class="mb">
            <p>标记后本次退步不计入门禁，<b>需要写明原因，并记入审计</b>。</p>
            <div class="field">
              <label>原因</label>
              <textarea v-model="expectReason" class="inp" :class="{ err: expectInvalid }" rows="3" placeholder="至少 10 个字" />
            </div>
          </div>
          <div class="mf">
            <button class="btn" type="button" @click="closeExpect">取消</button>
            <button v-write class="btn pri" type="button" @click="submitExpected">提交</button>
          </div>
        </div>
      </template>
    </Teleport>
  </div>
</template>

<style scoped>
textarea.inp { width: 100%; box-sizing: border-box; resize: vertical; }
</style>
