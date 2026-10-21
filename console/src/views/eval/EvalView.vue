<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AgentName from '@/components/AgentName.vue'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import { getActiveEvalRun, getEvalGates, getEvalRun, getLatestEval, publishEvaluatedAgent, runEval, type EvalGateRow, type EvalResult } from '@/api/eval'
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

const evalTab = ref<'cmp' | 'holdout' | 'confirm'>('cmp')
const route = useRoute()
const router = useRouter()
const agent = ref(typeof route.query.agent === 'string' ? route.query.agent : '')
const agentOptions = ref<{ name: string; displayName: string }[]>([])
const result = ref<EvalResult | null>(null)
const dimensionPage = ref(1)
const dimensionSize = ref<10 | 20 | 50 | 100>(10)
const dimensions = computed(() => result.value?.dimensions ?? [])
const dimensionRows = computed(() => dimensions.value.slice((dimensionPage.value - 1) * dimensionSize.value, dimensionPage.value * dimensionSize.value))
const gates = ref<EvalGateRow[]>([])
const pending = ref<{ jobId: string; agent: string }[]>([])
const gap = computed(() => holdoutGap(result.value?.scoreTotal, result.value?.holdout?.score))
const loading = ref(false)
const initialized = ref(false)
const progress = ref<number | null>(null)
const runError = ref('')
const recovering = ref(false)
const publishing = ref(false)
const published = ref('')
const expecting = ref(false)
const expectReason = ref('')
const expectInvalid = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
let loadSeq = 0
let resultRevision = 0

async function load() {
  const seq = ++loadSeq
  const revision = ++resultRevision
  const name = agent.value
  dimensionPage.value = 1
  published.value = ''
  runError.value = ''
  clearInterval(timer)
  progress.value = null
  void recoverRun(seq, name)
  loading.value = true
  try {
    const next = await getLatestEval(name)
    if (seq !== loadSeq || revision !== resultRevision || runError.value) return
    result.value = next
  } catch (error) {
    if (seq !== loadSeq || revision !== resultRevision) return
    result.value = null
    const keel = toKeelError(error)
    if (keel.code !== 'SERVER_NOT_FOUND') ElMessage.error(`加载评测结果失败：${keel.message}`)
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function pollRun(runId: string, seq: number) {
  clearInterval(timer)
  runError.value = ''
  progress.value = 0
  let busy = false
  const poll = async () => {
    if (busy || seq !== loadSeq) return
    busy = true
    try {
      const r = await getEvalRun(runId)
      if (seq !== loadSeq) return
      progress.value = r.progress ?? 0
      if (r.state === 'RUNNING' || r.state === 'QUEUED') return
      clearInterval(timer)
      progress.value = null
      if (r.result) { ++resultRevision; dimensionPage.value = 1; result.value = r.result }
      if (r.state === 'FAILED') {
        ++resultRevision
        result.value = null
        runError.value = `评测 ${runId} 执行失败：${r.error || '请检查运行服务和评分器'}。本次未形成有效门禁，请修复后重跑。`
        ElMessage.error(runError.value)
      }
      else ElMessage[r.result?.passed ? 'success' : 'warning'](`回归完成：总分 ${r.result?.scoreTotal ?? '—'}`)
    } catch (error) {
      if (seq !== loadSeq) return
      clearInterval(timer)
      progress.value = null
      runError.value = `读取评测任务失败：${toKeelError(error).message}，请刷新恢复任务。`
      ElMessage.error(`读取评测任务失败：${toKeelError(error).message}`)
    } finally {
      busy = false
    }
  }
  timer = setInterval(poll, 2000)
  void poll()
}

async function recoverRun(seq: number, name: string) {
  recovering.value = true
  try {
    const active = await getActiveEvalRun(name)
    if (seq === loadSeq && active?.runId) pollRun(active.runId, seq)
  } catch (error) {
    if (seq === loadSeq) {
      runError.value = `恢复评测进度失败：${toKeelError(error).message}，请刷新重试。`
      ElMessage.error(runError.value)
    }
  } finally {
    if (seq === loadSeq) recovering.value = false
  }
}

async function run() {
  if (!initialized.value || recovering.value || !agent.value || progress.value !== null) return
  const seq = loadSeq
  const name = agent.value
  progress.value = 0
  runError.value = ''
  try {
    const { runId } = await runEval(name)
    if (seq !== loadSeq) return
    if (runId) pollRun(runId, seq)
    else progress.value = null
  } catch (error) {
    if (seq !== loadSeq) return
    progress.value = null
    runError.value = `运行回归失败：${toKeelError(error).message}`
    ElMessage.error(runError.value)
    await recoverRun(seq, name)
  }
}

async function publish(env: 'staging' | 'prod') {
  const measured = result.value
  const name = agent.value
  if (!measured?.passed || !measured.complete || !measured.candidateVersion || publishing.value
      || recovering.value || runError.value || progress.value !== null) return
  publishing.value = true
  try {
    const run = await getEvalRun(measured.candidateVersion)
    await ElMessageBox.confirm(`智能体：${name}\n评测：${measured.candidateVersion}\n镜像：${run.image || '—'}\n将发布已评测版本${env === 'prod' ? '并使 production 提示词生效' : ''}。代码运行服务由部署流程维护。`,
      `发布版本到 ${env}`, { confirmButtonText: '确认发布', cancelButtonText: '取消' })
    const record = await publishEvaluatedAgent(name, measured.candidateVersion, env)
    if (name === agent.value) published.value = `${record.version || '—'} · ${record.env || env}`
    ElMessage.success(`已发布 ${name} ${record.version || ''} 到 ${env}`)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(`发布失败：${toKeelError(error).message}`)
  } finally {
    publishing.value = false
  }
}

function gapOf(row: EvalGateRow) {
  return row.jobId ? holdoutGap(row.visible, row.holdout) : null
}

function holdoutCell(row: EvalGateRow) {
  if (!row.jobId) return '—'
  return row.holdout == null ? '未接入' : `🔒 ${formatScore(row.holdout)}`
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
  await nextTick()
  if (agent.value && loadSeq === 0) void load()
  initialized.value = true
  await loadPending()
  await loadGates()
})
watch(() => envStore.env, () => {
  loadAgents()
  loadPending()
  loadGates()
})

async function loadGates() {
  try {
    const board = await getEvalGates(envStore.env)
    gates.value = board.rows ?? []
  } catch (error) {
    gates.value = []
    ElMessage.error(`加载研发任务门禁失败：${toKeelError(error).message}`)
  }
}

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
    const items = (page.items ?? []).flatMap((a) => a.status !== 'DRAFT' && a.name ? [{ name: a.name, displayName: a.displayName || a.name }] : [])
    const names = items.map((item) => item.name)
    agentOptions.value = items
    if (!names.includes(agent.value)) {
      agent.value = names[0] ?? ''
      if (!agent.value) result.value = null
    }
  } catch (error) {
    ElMessage.error(`加载智能体失败：${toKeelError(error).message}`)
  }
}
onBeforeUnmount(() => { ++loadSeq; clearInterval(timer) })
watch(agent, () => {
  if (agent.value) {
    void router.replace({ query: { ...route.query, agent: agent.value } })
    void load()
  }
})
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>测评中心</h2>
      <span class="sub">Langfuse 数据集 {{ result?.dataset ?? (agent || '—') }} · 门禁由 keel-gate 判定</span>
      <span class="sp" />
      <a v-if="result?.langfuseUrl" class="btn" :href="result.langfuseUrl" target="_blank" rel="noopener">在 Langfuse 中打开 ↗</a>
      <select v-model="agent" class="inp">
        <option v-for="a in agentOptions" :key="a.name" :value="a.name">{{ a.displayName }} · {{ a.name }}</option>
      </select>
      <button v-write class="btn pri" :disabled="!initialized || recovering || !agent || progress !== null" @click="run">▶ 运行回归</button>
    </div>

    <p v-if="progress !== null" role="status">回归正在排队或执行中，刷新页面可恢复进度。</p>
    <p v-if="runError" role="alert">{{ runError }}</p>

    <div class="chipsel" style="margin-bottom: 14px">
      <button :class="{ on: evalTab === 'cmp' }" @click="evalTab = 'cmp'">分维度对比</button>
      <button :class="{ on: evalTab === 'holdout' }" @click="evalTab = 'holdout'">研发任务门禁（隐藏考题）</button>
      <button :class="{ on: evalTab === 'confirm' }" @click="evalTab = 'confirm'">待评测确认 · {{ pending.length }}</button>
    </div>

    <div v-if="evalTab === 'confirm'" class="card">
      <h3>待评测确认<small>和审批中心是同一批待办</small></h3>
      <template v-if="pending.length">
        <RouterLink v-for="item in pending" :key="item.jobId" class="btn sm" :to="reviewPath(item.jobId)">{{ item.jobId }} <AgentName :id="item.agent" /> · 去确认</RouterLink>
      </template>
      <span v-else class="mut">无</span>
    </div>

    <div v-if="evalTab === 'holdout'" class="card">
      <h3>研发任务门禁</h3>
      <table v-if="gates.length" class="t">
        <thead><tr><th>智能体</th><th>来源</th><th>可见用例</th><th>隐藏考题（聚合）</th><th>分差</th><th>结论</th></tr></thead>
        <tbody>
          <tr v-for="row in gates" :key="`${row.jobId ?? 'manual'}-${row.agent}`">
            <td><AgentName :id="row.agent" /></td>
            <td>
              <RouterLink v-if="row.jobId" :to="`/jobs/${row.jobId}`">{{ row.jobId }}</RouterLink>
              <span v-else class="mut">人工编写</span>
            </td>
            <td class="mono">{{ row.visible == null ? '未接入' : formatScore(row.visible) }}</td>
            <td class="mono">{{ holdoutCell(row) }}</td>
            <td class="mono" :class="{ down: holdoutGapAlarm(gapOf(row)) }">{{ holdoutGapText(gapOf(row)) }}</td>
            <td>
              <StatusPill v-if="row.passed != null" :label="row.passed ? '通过' : '未通过'" :tone="row.passed ? 'ok' : 'failed'" />
              <span v-else>未接入</span>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="empty">还没有到达门禁的研发任务</p>
      <p class="mut">分差超过 0.10 标红告警，疑似针对可见用例特判。人工编写的智能体没有隐藏考题，列显示「—」。隐藏考题内容只对审核人开放。</p>
    </div>

    <template v-if="evalTab === 'cmp' && result && !runError">
      <p v-if="!result.complete" role="alert">这份历史结果未包含全部有效用例的完整性证据，不能用于页面发布，请重新运行。</p>
      <div class="gate" :class="{ pass: result.passed }">
        <div class="big">{{ formatScore(result.scoreTotal) }}</div>
        <div>
          <b>{{ result.passed ? (result.complete ? '门禁通过，可以发布' : '历史结论未核验完整性，请重新评测') : '门禁未通过，发布到 prod 已被阻止' }}</b><br />
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
        <template v-if="result.passed && result.complete && !result.devflowJobId">
          <button v-write class="btn" :disabled="publishing || recovering || !!runError || progress !== null" @click="publish('staging')">发布版本到 staging</button>
          <button v-write class="btn pri" :disabled="publishing || recovering || !!runError || progress !== null" @click="publish('prod')">发布版本到 prod</button>
        </template>
        <button v-if="!result.passed" class="btn" type="button" @click="markExpected">标记为预期变化</button>
      </div>
      <p v-if="published" role="status">已发布 {{ published }} · <RouterLink :to="{ path: '/agents', query: { drawer: agent } }">查看智能体版本</RouterLink></p>
      <p v-if="result.caseCount != null" class="mut">已执行 {{ result.caseCount }} 条用例<span v-if="result.complete"> · 全部有效用例已完成</span></p>
      <div v-if="progress !== null" class="prog"><i :style="{ width: `${progress * 100}%` }" /></div>

      <div v-if="evalTab === 'cmp'" class="card">
        <h3>分维度对比<small>prod 当前版本 vs 候选版本</small></h3>
        <table class="t">
          <thead><tr><th>维度（用例标签）</th><th>用例数</th><th>prod</th><th>候选</th><th>变化</th><th>判定</th></tr></thead>
          <tbody>
            <tr v-for="d in dimensionRows" :key="d.tag">
              <td>{{ d.tag }}</td>
              <td class="mono">{{ d.cases ?? '—' }}</td>
              <td class="mono">{{ formatScore(d.prodScore) }}</td>
              <td class="mono">{{ formatScore(d.candidateScore) }}</td>
              <td class="mono" :class="(d.deltaPt ?? 0) < 0 ? 'down' : 'up'">{{ deltaText(d.deltaPt) }}</td>
              <td><StatusPill v-bind="VERDICT[d.verdict ?? 'TOLERATED']" /></td>
            </tr>
            <tr v-if="!dimensionRows.length"><td colspan="6" class="empty">没有维度</td></tr>
          </tbody>
        </table>
        <Pager v-model:page="dimensionPage" v-model:size="dimensionSize" :total="dimensions.length" />
      </div>
      <div v-if="evalTab === 'cmp' && result.newFailures?.length" class="card">
        <h3>未通过评分的用例<small>总分通过门禁仍可能有个别评分未通过，请复核实际答复和预期。</small></h3>
        <table class="t">
          <thead><tr><th>用例 ID</th><th>调用链路</th></tr></thead>
          <tbody>
            <tr v-for="failure in result.newFailures" :key="`${failure.caseId}-${failure.traceId}`">
              <td class="mono">{{ failure.caseId || '—' }}</td>
              <td><RouterLink v-if="failure.traceId" :to="`/traces/${failure.traceId}`">查看调用链路</RouterLink><span v-else class="mut">没有链路 ID</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <template v-else-if="evalTab === 'cmp'">
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
