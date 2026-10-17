<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  approveDevflowToolGrant,
  refreshDevflowHumanReview,
  runDevflowCodeReview,
  requestDevflowH4,
  refreshDevflowH4,
  assistDevflowJob,
  cancelDevflowJob,
  getDevflowJob,
  getDevflowExecution,
  retryDevflowExecution,
  type DevflowExecution,
  getDevflowSettings,
  handbackDevflowJob,
  takeoverDevflowJob,
  type DevflowJob,
} from '@/api/devflow'
import { toKeelError } from '@/api/http'
import JobStatus from './JobStatus.vue'
import {
  DETAIL_POLL_MS,
  actorColor,
  artifacts,
  budgetHot,
  budgetWidth,
  canAssist,
  canCancel,
  canHandback,
  canTakeover,
  cny,
  detailNotices,
  handbackLabel,
  isLive,
  kindLabel,
  layerClass,
  layerShort,
  manifestYaml,
  repoLabel,
  seedTotal,
  showApprovalLink,
  timeline,
  visibleSeed,
} from './jobs'

const route = useRoute()
const job = ref<DevflowJob | null>(null)
const execution = ref<DevflowExecution | null>(null)
const loading = ref(false)
const missing = ref(false)
const busy = ref(false)
const tab = ref<'progress' | 'artifacts' | 'links'>('progress')
const modal = ref<'takeover' | 'assist' | 'cancel' | null>(null)
const instruction = ref('')
const manifestOpen = ref(false)
const concurrency = ref(3)

const jobId = computed(() => String(route.params.jobId ?? ''))
const notices = computed(() => (job.value ? detailNotices(job.value, concurrency.value) : []))
const rows = computed(() => (job.value ? timeline(job.value) : []))
const arts = computed(() => (job.value ? artifacts(job.value) : []))
const yaml = computed(() => (job.value ? manifestYaml(job.value) : ''))
const canCancelCurrentJob = computed(() => {
  if (!job.value) return false
  if (execution.value?.phase === 'FAILED' && job.value.stage === 'BUILD') return true
  if (execution.value?.phase === 'WAITING_REVIEW') return true
  return canCancel(job.value) && (!execution.value
    || ['WAITING', 'RUNNING'].includes(execution.value.phase)
    || (execution.value.phase === 'QUEUED' && !execution.value.draft))
})
const canRetryCurrentExecution = computed(() => {
  if (execution.value?.phase !== 'FAILED') return false
  if (!execution.value.error?.startsWith('五轮隔离沙箱测试')
    && !execution.value.error?.startsWith('五轮岗位代码修复额度')) return true
  const refreshes = Math.min(2, Math.max(0, execution.value.infrastructureRefreshes ?? 0))
  return (execution.value.sandboxAttempts?.length ?? 0) < 5 + refreshes
})

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const [loaded, work] = await Promise.all([getDevflowJob(jobId.value), getDevflowExecution(jobId.value)])
    job.value = loaded
    execution.value = work
    missing.value = false
    if (job.value.status === 'QUEUED') {
      concurrency.value = (await getDevflowSettings()).concurrency
    }
  } catch (error) {
    const keel = toKeelError(error)
    if (keel.code === 'SERVER_NOT_FOUND') {
      missing.value = true
      job.value = null
    } else if (!silent) {
      ElMessage.error(`加载任务失败：${keel.message}`)
    }
  } finally {
    loading.value = false
  }
}

async function run(action: () => Promise<DevflowJob>, done: string) {
  busy.value = true
  try {
    job.value = await action()
    modal.value = null
    instruction.value = ''
    ElMessage.success(done)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    busy.value = false
  }
}

async function retry() {
  busy.value = true
  try { execution.value = await retryDevflowExecution(jobId.value); await load(true) }
  catch (error) { ElMessage.error(toKeelError(error).message) }
  finally { busy.value = false }
}

async function approveTool(toolName: string) {
  busy.value = true
  try {
    execution.value = await approveDevflowToolGrant(jobId.value, toolName)
    ElMessage.success(`${toolName} 已记录 H3 人工授权`)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    busy.value = false
  }
}

async function refreshHumanReview() {
  busy.value = true
  try {
    execution.value = await refreshDevflowHumanReview(jobId.value)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    busy.value = false
  }
}

async function codeReview() {
  busy.value = true
  try { execution.value = await runDevflowCodeReview(jobId.value); await load(true) }
  catch (error) { ElMessage.error(toKeelError(error).message) }
  finally { busy.value = false }
}

async function requestH4() {
  busy.value = true
  try { execution.value = await requestDevflowH4(jobId.value); await load(true) }
  catch (error) { ElMessage.error(toKeelError(error).message) }
  finally { busy.value = false }
}

async function refreshH4() {
  busy.value = true
  try { execution.value = await refreshDevflowH4(jobId.value); await load(true) }
  catch (error) { ElMessage.error(toKeelError(error).message) }
  finally { busy.value = false }
}

function confirmAssist() {
  if (!job.value) return
  run(() => assistDevflowJob(job.value!.jobId, instruction.value), '已发给 dev-agent')
}

function openArtifact(kind: string) {
  if (kind === 'MANIFEST') {
    manifestOpen.value = true
    return
  }
  ElMessage.info('产物详情尚未接入，稍后从 Git 或评测中心打开')
}

function onVisible() {
  if (document.visibilityState === 'visible') load(true)
}

let timer = 0
function arm() {
  window.clearInterval(timer)
  if (!job.value || !isLive(job.value.status)) return
  timer = window.setInterval(() => {
    if (document.visibilityState !== 'hidden') load(true)
  }, DETAIL_POLL_MS)
}

watch(jobId, () => {
  tab.value = 'progress'
  modal.value = null
  manifestOpen.value = false
  load()
})
watch(() => job.value?.status, arm)

onMounted(() => {
  document.addEventListener('visibilitychange', onVisible)
  load()
})
onUnmounted(() => {
  window.clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisible)
})
</script>

<template>
  <div v-loading="loading">
    <div v-if="missing" class="empty">任务不存在</div>
    <template v-else-if="job">
      <div class="vh">
        <span class="ly" :class="layerClass(job.layer)">{{ layerShort(job.layer) }}</span>
        <h2>{{ job.title }}</h2>
        <span class="mono mut">{{ job.jobId }}</span>
        <JobStatus :job="job" />
        <span class="sp" />
        <RouterLink v-if="showApprovalLink(job) && (!execution || ['WAITING', 'WAITING_SEEDS', 'WAITING_EVAL', 'WAITING_H4'].includes(execution.phase))" class="btn pri" :to="`/approvals/devflow/${encodeURIComponent(job.jobId)}`">去审批中心处理</RouterLink>
        <button v-if="!execution && canTakeover(job)" v-write class="btn vio" type="button" @click="modal = 'takeover'">人工接管开发</button>
        <template v-if="!execution && canHandback(job)">
          <button v-if="canAssist(job)" v-write class="btn vio" type="button" @click="modal = 'assist'">请智能体帮忙</button>
          <button v-write class="btn pri" type="button" :disabled="busy" @click="run(() => handbackDevflowJob(job!.jobId), job.mode === 'SCAFFOLD' ? '已提交门禁' : '已交还')">{{ handbackLabel(job.mode) }}</button>
        </template>
        <button v-if="canCancelCurrentJob" v-write class="btn danger" type="button" @click="modal = 'cancel'">取消</button>
      </div>

      <template v-if="execution">
        <div class="banner" :class="execution.phase === 'FAILED' ? 'warn' : 'info'">
          <b>{{ execution.phase === 'WAITING_REVIEW' ? '沙箱通过 · 等待 S4 代码与人工评审' : execution.phase === 'STAGING_READY' ? 'S4 已通过 · 等待 staging 门禁' : execution.phase === 'STAGING_GATE_PASSED' ? 'staging 门禁通过 · 待申请 H4' : execution.phase === 'WAITING_H4' ? '等待 H4 合并审批' : execution.phase === 'RELEASING' ? (execution.prodGate?.passed ? 'prod 门禁通过 · 等待 CI 发布' : 'H4 已合并 · 等待 prod 门禁') : execution.phase === 'WATCHING' ? 'prod 已发布 · 七天质量与成本观察中' : execution.phase === 'DONE' ? '七天观察完成 · 数字员工已上岗' : execution.phase === 'SANDBOX_PENDING' || execution.phase === 'SANDBOX_RUNNING' ? '候选代码正在隔离沙箱验证' : execution.phase === 'WAITING_SEEDS' ? '等待人工种子用例' : execution.phase === 'WAITING_EVAL' ? '等待 H2 评测确认' : execution.phase === 'WAITING' ? '等待 H1 需求确认' : execution.phase === 'FAILED' ? '执行失败' : execution.phase === 'CANCELLED' ? '任务已取消' : '元智能体处理中' }}</b>
          <span>{{ execution.error || '元智能体生产任务；沙箱、H2/H3、人工评审、staging 门禁、H4 与发布须逐项完成。' }}</span>
          <button v-if="canRetryCurrentExecution" v-write class="btn sm" :disabled="busy" :title="execution.error?.startsWith('五轮') ? '模板刷新不占五轮岗位代码修复额度' : undefined" @click="retry">重试执行</button>
          <button v-if="execution.phase === 'WAITING_REVIEW' && (execution.infrastructureRefreshes ?? 0) < 2" v-write class="btn sm" :disabled="busy" @click="retry">刷新候选交付</button>
          <button v-if="execution.phase === 'WAITING_REVIEW' && (execution.infrastructureRefreshes ?? 0) >= 2 && (execution.reviewRepairRounds ?? 0) < 1" v-write class="btn sm" :disabled="busy" @click="retry">修复岗位功能</button>
          <button v-if="execution.phase === 'WAITING_REVIEW' && (execution.infrastructureRefreshes ?? 0) >= 2 && (execution.reviewRepairRounds ?? 0) >= 1 && (execution.sandboxAttempts?.length ?? 0) === 8 && execution.modelReview?.approved !== false" v-write class="btn sm" :disabled="busy" @click="retry">修复候选 CI</button>
          <button v-if="execution.phase === 'WAITING_REVIEW' && execution.humanReview?.approved && execution.humanReview.headSha === execution.delivery?.sha && execution.modelReview?.approved === false && execution.modelReview.sha === execution.delivery?.sha && execution.modelReview.findings?.some(finding => finding.severity === 'HIGH' || finding.severity === 'MID') && (execution.s4RepairRounds ?? 0) < 1 && [8, 10].includes(execution.sandboxAttempts?.length ?? 0)" v-write class="btn sm" :disabled="busy" @click="retry">修复 S4 评审问题</button>
        </div>
        <div class="kpis">
          <div class="kpi"><div class="l">目标智能体</div><div class="v sm">{{ job.targetAgent }}</div></div>
          <div class="kpi"><div class="l">需求草稿</div><div class="v sm">第 {{ execution.version }} 版</div></div>
          <div class="kpi"><div class="l">人工修改轮次</div><div class="v sm">{{ execution.revisions }}/3</div></div>
        </div>
        <div v-if="execution.pendingToolGrants" class="card">
          <h3>H3 · 候选员工工具授权</h3>
          <p>平台管理员逐项确认后，候选员工登记时只能获得这里批准的工具版本。</p>
          <div v-for="toolName in execution.pendingToolGrants" :key="toolName" class="kv">
            <span>{{ toolName }}</span>
            <span>v1 · {{ job.tools.find(tool => tool.name === toolName)?.risk || '待核对' }}</span>
            <span>状态</span>
            <button v-write class="btn sm" type="button" :disabled="busy" @click="approveTool(toolName)">批准此工具</button>
          </div>
          <div v-if="execution.pendingToolGrants.length === 0">全部岗位工具已获 H3 人工授权。</div>
          <div v-for="grant in execution.toolGrants || []" :key="grant.toolName" class="mono mut">
            {{ grant.toolName }} {{ grant.versionRange }} · {{ grant.grantedBy }}
          </div>
        </div>
        <div v-if="execution.delivery" class="card">
          <h3>岗位代码候选</h3>
          <div v-if="execution.delivery.protection === 'UNAVAILABLE'" class="banner warn">当前 GitHub 套餐不支持此私有仓库的分支保护。人工 PR approve 无法强制，发布关口阻塞。</div>
          <div v-if="execution.delivery.checks === 'MANUAL'" class="banner warn">此仓库的自动检查尚未启用。工作流模板在 ci/keel.yml，需有工作流写入权限的维护者安装到 .github/workflows 后运行。</div>
          <p v-else-if="execution.delivery.checks === 'ENABLED'" class="mut">工作流在 .github/workflows/keel.yml，首次推送会触发 CI。这不代表测试或门禁已经通过。</p>
          <div class="kv">
            <span>Pull Request</span><a :href="execution.delivery.url" target="_blank" rel="noopener noreferrer">{{ execution.delivery.repo }} #{{ execution.delivery.number }} ↗</a>
            <span>分支</span><span class="mono">{{ execution.delivery.branch }}</span>
            <span>Commit</span><span class="mono">{{ execution.delivery.sha }}</span>
            <span>验证状态</span><span>{{ execution.delivery.capabilityStatus || 'UNVERIFIED' }} · 门禁 {{ execution.delivery.gateStatus || 'NOT_RUN' }}</span>
            <template v-if="execution.sandboxRunId"><span>隔离沙箱</span><span class="mono">{{ execution.sandboxRunId }} · {{ execution.sandboxStatus || 'RUNNING' }}</span></template>
            <span>下一步</span><span>补齐 H2/H3、沙箱、人工 PR review 与门禁证据；未完成前不可发布。</span>
          </div>
          <div v-if="execution.sandboxAttempts?.length" class="card">
            <h3>隔离沙箱验证记录 · {{ execution.sandboxAttempts.length }} 次</h3>
            <p class="mut">岗位代码沙箱修正最多 5 轮；平台模板刷新 {{ execution.infrastructureRefreshes ?? 0 }}/2 次；评审阶段功能修复 {{ execution.reviewRepairRounds ?? 0 }}/1 次；候选 CI 修复 {{ (execution.sandboxAttempts?.length ?? 0) >= 11 || ((execution.sandboxAttempts?.length ?? 0) >= 9 && !execution.s4RepairRounds) ? 1 : 0 }}/1 次；S4 专项修复 {{ execution.s4RepairRounds ?? 0 }}/1 次。每次均重新验证提交。</p>
            <div v-for="attempt in execution.sandboxAttempts" :key="attempt.runId" class="kv">
              <span>第 {{ attempt.attempt }} 轮</span><b>{{ attempt.status === 'PASS' ? '通过' : '失败' }} · {{ attempt.runId }}</b>
              <span>候选提交</span><span class="mono">{{ attempt.sha }}</span>
              <span>测试报告</span><pre class="code">{{ attempt.report }}</pre>
            </div>
          </div>
          <div v-if="execution.phase === 'WAITING_REVIEW'" class="kv">
            <span>S4 人工评审</span>
            <button v-write class="btn sm" type="button" :disabled="busy" @click="refreshHumanReview">核验 PR review</button>
            <span>核验结果</span>
            <span v-if="execution.humanReview">
              {{ execution.humanReview.approved ? '通过' : '未通过' }} · 分支保护 {{ execution.humanReview.protection }} ·
              {{ execution.humanReview.ready ? '已可评审' : '仍是草稿' }} ·
              {{ execution.humanReview.reviewers.join('、') || '无有效审批人' }}
            </span>
            <span v-else>尚未核验</span>
            <span>模型代码评审</span>
            <button v-write class="btn sm" type="button" :disabled="busy || !execution.humanReview?.approved || !!execution.pendingToolGrants?.length" @click="codeReview">运行 S4 代码评审</button>
          </div>
          <div v-if="execution.modelReview" class="kv">
            <span>S4 代码评审</span><span>{{ execution.modelReview.approved ? '通过' : '待修复' }} · {{ execution.modelReview.reviewer }}</span>
            <template v-for="(finding, index) in execution.modelReview.findings" :key="index">
              <span>{{ finding.severity }} · {{ finding.path }}:{{ finding.line }}</span><span>{{ finding.detail }}</span>
            </template>
          </div>
          <div v-if="execution.gate" class="kv">
            <span>staging 门禁</span><span>{{ execution.gate.passed ? '通过' : '未通过' }} · {{ execution.gate.runId }}</span>
            <span>候选提交</span><span class="mono">{{ execution.gate.sha }}</span>
            <span>预览镜像</span><span class="mono">{{ execution.gate.image }}</span>
            <span>可见 / 隐藏分</span><span>{{ execution.gate.visibleScore }} / {{ execution.gate.hiddenScore }}</span>
          </div>
          <div v-if="execution.phase === 'STAGING_GATE_PASSED'" class="kv">
            <span>H4 发布审批</span><button v-write class="btn sm" type="button" :disabled="busy" @click="requestH4">申请高风险合并审批</button>
          </div>
          <div v-if="execution.h4ApprovalId" class="kv"><span>H4 审批单</span><RouterLink :to="`/approvals/devflow/${encodeURIComponent(job.jobId)}`">{{ execution.h4ApprovalId }} →</RouterLink></div>
          <div v-if="execution.phase === 'WAITING_H4'" class="kv"><span>H4 状态</span><button v-write class="btn sm" type="button" :disabled="busy" @click="refreshH4">核验审批并合并 PR</button></div>
          <div v-if="execution.mergeSha" class="kv"><span>受保护合并提交</span><span class="mono">{{ execution.mergeSha }}</span></div>
          <div v-if="execution.prodGate" class="kv">
            <span>prod 门禁</span><span>{{ execution.prodGate.passed ? '通过' : '未通过' }} · {{ execution.prodGate.runId }}</span>
            <span>合并提交</span><span class="mono">{{ execution.prodGate.sha }}</span>
            <span>实测镜像</span><span class="mono">{{ execution.prodGate.image }}</span>
            <span>可见 / 隐藏分</span><span>{{ execution.prodGate.visibleScore }} / {{ execution.prodGate.hiddenScore }}</span>
          </div>
          <div v-if="execution.watchStartedAt" class="kv">
            <span>S7 观察起点</span><span>{{ execution.watchStartedAt }}</span>
            <span>生产镜像</span><span class="mono">{{ execution.watchImage }}</span>
            <span>有证据的观察日</span><span>{{ execution.watchObservations?.length || 0 }} / 7</span>
            <template v-for="sample in execution.watchObservations || []" :key="sample.day">
              <span>{{ sample.day }} · {{ sample.healthy ? '正常' : '异常' }}</span>
              <span>质量 {{ sample.quality }} · 成本 ¥{{ sample.costCny }} / ¥{{ sample.budgetCny }} · 调用 {{ sample.calls }}</span>
            </template>
          </div>
        </div>
        <div v-if="execution.draft?.spec" class="row2">
          <div class="card">
            <h3>需求单 · 第 {{ execution.version }} 版</h3>
            <div class="kv">
              <span>标题</span><b>{{ execution.draft.spec.title }}</b>
              <span>目标</span><span>{{ execution.draft.spec.goal }}</span>
              <span>场景</span><span>{{ execution.draft.spec.users }}</span>
              <span>输入输出</span><span>{{ execution.draft.spec.io }}</span>
              <span>成功标准</span><span>{{ execution.draft.spec.success }}</span>
            </div>
          </div>
          <div class="card"><h3>agent.yaml · 元智能体实际产出</h3><pre class="code">{{ execution.draft.manifestYaml }}</pre></div>
        </div>
        <div class="card">
          <h3>执行记录</h3>
          <ul class="log"><li v-for="(event, index) in [...job.events].reverse()" :key="index"><span class="tm">{{ event.at }}</span><span class="who">{{ event.actor }}</span><span>{{ event.summary }}</span></li></ul>
          <RouterLink v-if="execution.traceId" :to="`/traces/${execution.traceId}`">查看最近一次执行链路 →</RouterLink>
        </div>
      </template>
      <template v-else>
      <div v-for="(note, index) in notices" :key="index" class="banner" :class="note.tone">
        <b v-if="note.title" :class="note.tone === 'vio' ? 'vioc' : ''">{{ note.title }}</b>
        <span>{{ note.text }}</span>
      </div>

      <div class="kpis">
        <div class="kpi">
          <div class="l">产出智能体</div>
          <div class="v sm">{{ job.targetAgent }}</div>
          <div class="d">{{ kindLabel(job.kind) }} · {{ job.template }}</div>
        </div>
        <div class="kpi">
          <div class="l">生产者 · 开发模式</div>
          <div class="v sm">{{ job.producerAgent }}</div>
          <div class="d">{{ job.mode === 'AUTO' ? '全托管' : job.mode === 'COLLAB' ? '人机协作' : '只生成骨架' }}</div>
        </div>
        <div class="kpi">
          <div class="l">花费 / 预算</div>
          <div class="v">{{ cny(job.spentCny) }}</div>
          <div class="bar" style="margin-top: 6px"><i :style="{ width: `${budgetWidth(job.spentCny, job.budgetCny)}%`, background: budgetHot(job.spentCny, job.budgetCny) ? 'var(--bad)' : 'var(--teal)' }" /></div>
        </div>
        <div class="kpi">
          <div class="l">修复轮次</div>
          <div class="v" :class="{ badc: job.fixRounds >= job.maxFixRounds }">{{ job.fixRounds }}/{{ job.maxFixRounds }}</div>
          <div class="d">{{ job.status === 'HUMAN' ? '人工开发中暂停计数' : '相同失败两次直接失败' }}</div>
        </div>
        <div class="kpi">
          <div class="l">评测用例</div>
          <div class="v">{{ seedTotal(job.seed) || '—' }}</div>
          <div class="d">人 {{ job.seed.human }} · 智能体 {{ job.seed.agent }} · 隐藏 {{ job.seed.holdout }}</div>
        </div>
      </div>

      <div class="dtabs">
        <button type="button" :class="{ on: tab === 'progress' }" @click="tab = 'progress'">进度</button>
        <button type="button" :class="{ on: tab === 'artifacts' }" @click="tab = 'artifacts'">产物</button>
        <button type="button" :class="{ on: tab === 'links' }" @click="tab = 'links'">评测 · 链路 · 成本</button>
      </div>

      <template v-if="tab === 'progress'">
        <div class="row2">
          <div class="card">
            <h3>阶段时间线</h3>
            <div class="tl">
              <div v-for="row in rows" :key="row.key" class="it">
                <div class="dot" :class="[row.mark, { h: row.human }]">{{ row.glyph }}</div>
                <div class="nm2"><b>{{ row.title }}</b><small>{{ row.detail }}</small></div>
                <div class="rt">{{ row.result }}</div>
              </div>
            </div>
          </div>
          <div class="card">
            <h3>动态 <small>只记结论和产物引用</small></h3>
            <ul class="log">
              <li v-if="!job.events.length"><span class="tm">—</span><span class="who">—</span><span>暂无</span></li>
              <li v-for="(event, index) in [...job.events].reverse()" :key="index">
                <span class="tm">{{ event.at }}</span>
                <span class="who" :style="{ color: actorColor(event.actor) }">{{ event.actor }}</span>
                <span>{{ event.summary }}</span>
              </li>
            </ul>
          </div>
        </div>
        <div class="card">
          <h3>需求</h3>
          <div class="kv">
            <span>目标</span><span>{{ job.goal }}</span>
            <span>工具</span>
            <span>
              <template v-if="job.tools.length">
                <span v-for="tool in job.tools" :key="tool.name" class="chip">{{ tool.name }} · <b>{{ tool.risk.toLowerCase() }}</b></span>
              </template>
              <template v-else>—</template>
            </span>
            <span>仓库</span><span class="mono">{{ repoLabel(job) }}</span>
            <span>负责人</span><span>{{ job.requester }}（{{ job.ownerOrg }}）</span>
          </div>
        </div>
      </template>

      <div v-else-if="tab === 'artifacts'" class="card">
        <h3>产物 <small>只存引用（git sha、数据集名、报告 id）</small></h3>
        <table class="t">
          <tbody>
            <tr v-for="item in arts" :key="item.kind">
              <td><span class="pill nd p-soft">{{ item.kind }}</span></td>
              <td>{{ item.label }}</td>
              <td><span v-if="item.ready">{{ item.origin }}</span><span v-else class="mut">未产出</span></td>
              <td>
                <button v-if="item.ready" class="btn sm" type="button" @click="openArtifact(item.kind)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="row2e">
        <div class="card">
          <h3>评测</h3>
          <div class="kv">
            <span>人给（可见）</span><span class="mono">{{ visibleSeed(job.seed) }}</span>
            <span>智能体扩充</span><span class="mono">{{ job.seed.agent }}</span>
            <span>隐藏考题</span><span class="lock">🔒 {{ job.seed.holdout }}（只显示条数）</span>
          </div>
          <div style="margin-top: 10px"><RouterLink class="btn sm" to="/eval">在评测中心看门禁结果 →</RouterLink></div>
        </div>
        <div class="card">
          <h3>链路与成本</h3>
          <div class="kv">
            <span>trace</span><span class="mono">一个任务一条，根节点带 keel.devflow.job_id</span>
            <span>花费</span><span class="mono">{{ cny(job.spentCny) }}（薄网关 costCny）</span>
          </div>
          <div style="margin-top: 10px; display: flex; gap: 8px">
            <RouterLink class="btn sm" to="/traces">在链路追踪里看 →</RouterLink>
            <RouterLink class="btn sm" to="/models">在模型网关看成本 →</RouterLink>
          </div>
        </div>
      </div>
      </template>
    </template>

    <div v-if="modal" class="mask on" @click="modal = null" />
    <div v-if="modal === 'takeover'" class="modal on" role="dialog" aria-label="人工接管">
      <div class="mh">人工接管 {{ jobId }}</div>
      <div class="mb">
        <ul class="plain">
          <li>dev-agent 停止推送，任务进入「人工开发」</li>
          <li>修复轮次暂停计数</li>
          <li>你在 <span class="mono">keel-agents/{{ job?.targetAgent }}</span> 的 PR 上直接提交</li>
          <li>记 config.change 审计（kind=devflow.takeover）</li>
        </ul>
      </div>
      <div class="mf">
        <button class="btn" type="button" @click="modal = null">取消</button>
        <button class="btn vio" type="button" :disabled="busy" @click="run(() => takeoverDevflowJob(jobId), '已接管')">确认接管</button>
      </div>
    </div>
    <div v-if="modal === 'assist'" class="modal on" role="dialog" aria-label="请智能体帮忙">
      <div class="mh">请智能体帮忙</div>
      <div class="mb">
        <div class="field">
          <label>指令（写明范围）</label>
          <textarea v-model="instruction" class="inp" placeholder="如：在 tools/ 下补齐单测，不要改 app.py" />
        </div>
      </div>
      <div class="mf">
        <button class="btn" type="button" @click="modal = null">取消</button>
        <button class="btn pri" type="button" :disabled="busy" @click="confirmAssist">发给 dev-agent</button>
      </div>
    </div>
    <div v-if="modal === 'cancel'" class="modal on" role="dialog" aria-label="取消任务">
      <div class="mh">取消 {{ jobId }}</div>
      <div class="mb">取消后不再接受草稿结果；已有记录会保留。</div>
      <div class="mf">
        <button class="btn" type="button" @click="modal = null">再想想</button>
        <button class="btn danger" type="button" :disabled="busy" @click="run(() => cancelDevflowJob(jobId), '已取消')">确认取消</button>
      </div>
    </div>

    <div class="mask" :class="{ on: manifestOpen }" @click="manifestOpen = false" />
    <aside class="drawer" :class="{ on: manifestOpen }">
      <div class="dh">
        <h3>agent.yaml · {{ job?.targetAgent }}</h3>
        <span class="sp" />
        <button class="x" type="button" @click="manifestOpen = false">×</button>
      </div>
      <div class="db"><pre class="code">{{ yaml }}</pre></div>
    </aside>
  </div>
</template>
