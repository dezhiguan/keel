<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AgentName from '@/components/AgentName.vue'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import { listDevflowJobs } from '@/api/devflow'
import { getOverview, type Overview } from '@/api/overview'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { agentStatus, fmtN } from '@/utils/format'
import { agentType } from '@/views/agents/employees'
import { ALERT_KIND, OVERVIEW_RANGES, fmtMoney, fmtScore, fmtSeconds, hm, layerDays, layerPair, layerPct, layerScore, metricByKey, metricValue, overviewRangeLabel, pipelineCards, pipelineMoney, techLabel, trendText, type OverviewRange, type PipelineCards } from './overviewFormat'

type Category = 'all' | 'biz' | 'dev' | 'meta'

const CATEGORIES: [Category, string][] = [['all', '全部'], ['biz', '业务智能体'], ['dev', '研发智能体'], ['meta', '元智能体']]

const route = useRoute()
const router = useRouter()
const envStore = useEnvStore()
const overview = ref<Overview | null>(null)
const pipeline = ref<PipelineCards>(pipelineCards(null))
const loading = ref(false)
const category = ref<Category>('all')
const range = ref<OverviewRange>('24h')
const panel = ref<'agents' | 'alerts' | 'cost' | 'value'>('agents')
const page = ref(1)
const size = ref<10 | 20 | 50 | 100>(10)
let loadSequence = 0

const envLabel = computed(() => (envStore.env === 'all' ? '全部环境' : envStore.env))
const kpi = computed(() => overview.value?.kpi)
const cumulative = computed(() => overview.value?.cumulative)
function tip(key: string) {
  const metric = metricByKey(overview.value?.metrics, key)
  return metric ? `口径：${metric.definition ?? ''}\n来源：${metric.source ?? ''}` : undefined
}
function shown(key: string, text: string) {
  return metricValue(metricByKey(overview.value?.metrics, key)?.connected, text)
}
const gate = computed(() => kpi.value?.gateThreshold ?? 0.85)
const trend = computed(() => trendText(kpi.value?.callsTrendPct))
const uncategorized = computed(() => {
  const total = kpi.value?.totalAgents ?? 0
  const known = (kpi.value?.bizCount ?? 0) + (kpi.value?.devCount ?? 0)
  return Math.max(0, total - known)
})
const filtered = computed(() => (overview.value?.agents ?? []).filter((agent) => category.value === 'all' || agentType(agent) === category.value))
const rows = computed(() => filtered.value.slice((page.value - 1) * size.value, page.value * size.value))
const bars = computed(() =>
  [...(overview.value?.costByAgent ?? [])]
    .filter((row) => (row.costCny ?? 0) > 0)
    .sort((a, b) => (b.costCny ?? 0) - (a.costCny ?? 0))
    .slice(0, 6),
)

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  const [overviewResult, jobsResult] = await Promise.allSettled([
    getOverview({ env: envStore.env, range: range.value }),
    listDevflowJobs(),
  ])
  if (sequence !== loadSequence) return
  if (overviewResult.status === 'fulfilled') {
    overview.value = overviewResult.value
  } else {
    ElMessage.error(`加载总览失败：${toKeelError(overviewResult.reason).message}`)
  }
  pipeline.value = pipelineCards(jobsResult.status === 'fulfilled' ? jobsResult.value : null)
  loading.value = false
}

function open(name?: string) {
  if (!name) return
  router.push({ query: { ...route.query, drawer: name } })
}

function setCategory(next: Category) {
  category.value = next
  page.value = 1
}

function belowGate(score?: number | null) {
  return score !== null && score !== undefined && score < gate.value
}

function barWidth(cost?: number, budget?: number) {
  if (!budget || budget <= 0) return Math.min(100, ((cost ?? 0) / (bars.value[0]?.costCny || 1)) * 100)
  return Math.min(100, ((cost ?? 0) / budget) * 100)
}

function barColor(cost?: number, budget?: number) {
  if (!budget || budget <= 0) return 'var(--teal)'
  const pct = barWidth(cost, budget)
  if (pct > 80) return 'var(--bad)'
  if (pct > 60) return 'var(--warn)'
  return 'var(--teal)'
}

watch([() => envStore.env, range], () => {
  page.value = 1
  load()
}, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>运行总览</h2>
      <span class="sub">{{ envLabel }} · {{ overviewRangeLabel(range) }}</span>
      <span class="sp" />
      <div class="chipsel" aria-label="总览时间窗">
        <button v-for="[value, label] in OVERVIEW_RANGES" :key="value" :class="{ on: range === value }" @click="range = value">{{ label }}</button>
      </div>
      <span class="envtag">研发任务产出只进 staging · 上生产需人批</span>
      <button v-write class="btn pri" @click="router.push('/agents/new')">+ 新建智能体</button>
    </div>

    <div class="kpis">
      <div class="kpi">
        <div class="l">在线智能体</div>
        <div class="v">{{ kpi?.onlineAgents ?? '—' }}<span class="mut frac"> / {{ kpi?.totalAgents ?? '—' }}</span></div>
        <div class="d">业务 {{ kpi?.bizCount ?? 0 }} · 研发 {{ kpi?.devCount ?? 0 }}<template v-if="uncategorized"> · 未分类 {{ uncategorized }}</template></div>
      </div>
      <div class="kpi" :title="tip('calls')">
        <div class="l">调用次数</div>
        <div class="v">{{ shown('calls', fmtN(kpi?.calls)) }}</div>
        <div v-if="trend" class="d" :class="trend.tone">{{ trend.text }}</div>
        <div v-else class="d">{{ overviewRangeLabel(range) }}</div>
      </div>
      <div class="kpi" :title="tip('modelCost')">
        <div class="l">模型成本</div>
        <div class="v">{{ shown('modelCost', fmtMoney(kpi?.modelCostCny)) }}</div>
        <div class="d">待计费 {{ kpi?.pendingModelBills ?? 0 }} 条 · 已结算金额</div>
      </div>
      <div class="kpi">
        <div class="l">平均评测分</div>
        <div class="v">{{ fmtScore(kpi?.avgScore) }}</div>
        <div class="d">门禁 {{ fmtScore(gate) }}</div>
      </div>
      <div class="kpi">
        <div class="l">待办审批</div>
        <div class="v" :style="(kpi?.pendingApprovals ?? 0) > 0 ? { color: 'var(--warn)' } : undefined">{{ kpi?.pendingApprovals ?? '—' }}</div>
        <div class="d"><RouterLink to="/approvals">去处理 →</RouterLink></div>
      </div>
    </div>

    <div class="kpis">
      <div class="kpi" :title="tip('agents')">
        <div class="l">在册智能体</div>
        <div class="v">{{ cumulative?.agents ?? '—' }}</div>
        <div class="d">含已下线</div>
      </div>
      <div class="kpi" :title="tip('cumulativeCalls')">
        <div class="l">累计调用</div>
        <div class="v">{{ shown('cumulativeCalls', fmtN(cumulative?.calls)) }}</div>
        <div class="d">{{ cumulative?.since ? `自 ${cumulative.since} 起` : '自日账上线起' }}</div>
      </div>
      <div class="kpi" :title="tip('cumulativeTokens')">
        <div class="l">累计 token</div>
        <div class="v">{{ shown('cumulativeTokens', fmtN(cumulative?.tokens)) }}</div>
        <div class="d">输入 + 输出</div>
      </div>
      <div class="kpi" :title="tip('cumulativeCost')">
        <div class="l">累计模型花费</div>
        <div class="v">{{ shown('cumulativeCost', fmtMoney(cumulative?.costCny)) }}</div>
        <div class="d">已结算 costCny</div>
      </div>
      <div class="kpi" :title="tip('cumulativeUsers')">
        <div class="l">累计服务用户</div>
        <div class="v">{{ shown('cumulativeUsers', fmtN(cumulative?.users)) }}</div>
        <div class="d">去重</div>
      </div>
    </div>

    <div class="kpis">
      <div class="kpi">
        <div class="l">进行中研发任务</div>
        <div class="v">{{ pipeline.active ?? '—' }}</div>
        <div class="d">排队 {{ pipeline.queued ?? '—' }}</div>
      </div>
      <div class="kpi">
        <div class="l">等人处理</div>
        <div class="v" :class="{ warnc: (pipeline.waiting ?? 0) > 0 }">{{ pipeline.waiting ?? '—' }}</div>
        <div class="d"><RouterLink to="/approvals">在审批中心统一处理</RouterLink></div>
      </div>
      <div class="kpi">
        <div class="l">本月上线</div>
        <div class="v">{{ pipeline.shipped ?? '—' }}</div>
        <div class="d">由流水线生产</div>
      </div>
      <div class="kpi">
        <div class="l">首次门禁通过率</div>
        <div class="v">{{ pipeline.firstPassPct == null ? '—' : `${pipeline.firstPassPct}%` }}</div>
        <div class="d">目标 85%（3 轮内）</div>
      </div>
      <div class="kpi">
        <div class="l">本月研发花费</div>
        <div class="v">{{ pipelineMoney(pipeline.spentCny) }}</div>
        <div class="d"><RouterLink to="/models">明细见模型网关</RouterLink></div>
      </div>
    </div>
    <p v-if="pipeline.active == null" class="pipe-note">研发任务账本尚未接入，以上数字为占位。</p>

    <div class="chipsel" style="margin-bottom: 14px">
      <button :class="{ on: panel === 'agents' }" @click="panel = 'agents'">智能体 · {{ filtered.length }}</button>
      <button :class="{ on: panel === 'alerts' }" @click="panel = 'alerts'">告警与事件 · {{ overview?.alerts?.length ?? 0 }}</button>
      <button :class="{ on: panel === 'cost' }" @click="panel = 'cost'">{{ overviewRangeLabel(range) }}成本</button>
      <button :class="{ on: panel === 'value' }" @click="panel = 'value'">分层价值</button>
    </div>

    <section v-if="panel === 'agents'" class="card">
      <h3>智能体<small>点击行查看详情</small><span class="sp" /><div class="chipsel">
        <button v-for="[key, label] in CATEGORIES" :key="key" :class="{ on: category === key }" @click="setCategory(key)">{{ label }}</button>
      </div></h3>
      <table class="t">
        <thead>
          <tr>
            <th>智能体</th><th>类型</th><th>技术</th><th>环境</th><th>版本</th>
            <th>24h 调用</th><th>总调用</th><th>P95</th><th>成本</th><th>评测分</th><th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!rows.length"><td colspan="11" class="empty">当前环境没有智能体</td></tr>
          <tr v-for="agent in rows" :key="agent.name" class="click" @click="open(agent.name)">
            <td><AgentName :id="agent.name" :display-name="agent.displayName" /></td>
            <td>
              <span v-if="agentType(agent) === 'meta'" class="pill nd p-warn">元智能体</span>
              <span v-else-if="agentType(agent) === 'biz'" class="pill nd p-teal">业务</span>
              <span v-else-if="agentType(agent) === 'dev'" class="pill nd p-acc">研发</span>
              <span v-else class="mut">—</span>
            </td>
            <td>{{ techLabel(agent) }}</td>
            <td>{{ agent.env || '—' }}</td>
            <td class="mono">{{ agent.version || '—' }}</td>
            <td class="mono">{{ fmtN(agent.calls24h) }}</td>
            <td class="mono">{{ fmtN(agent.callsTotal) }}</td>
            <td class="mono">{{ fmtSeconds(agent.p95Seconds) }}</td>
            <td class="mono">{{ fmtMoney(agent.costCny) }}</td>
            <td class="mono" :style="belowGate(agent.score) ? { color: 'var(--bad)' } : undefined">{{ fmtScore(agent.score) }}</td>
            <td>
              <StatusPill v-bind="agentStatus(agent.status)" />
              <div v-if="agent.statusNote" class="mut note">{{ agent.statusNote }}</div>
            </td>
          </tr>
        </tbody>
      </table>
      <Pager v-model:page="page" v-model:size="size" :total="filtered.length" />
    </section>

    <section v-else-if="panel === 'alerts'" class="card">
        <h3>告警与事件</h3>
        <p v-if="!overview?.alerts?.length" class="empty">暂无告警</p>
        <ul v-else class="alerts">
          <li v-for="(alert, index) in overview.alerts" :key="`${alert.agent}-${alert.kind}-${index}`">
            <span class="tm">{{ hm(alert.at) }}</span>
            <span class="pill nd" :class="alert.kind === 'LLM_USAGE_MISSING' ? 'p-warn' : ALERT_KIND[alert.kind || '']?.[1] || 'p-mute'">{{ alert.kind === 'LLM_USAGE_MISSING' ? '网关' : ALERT_KIND[alert.kind || '']?.[0] || alert.level }}</span>
            <span>{{ alert.text }}</span>
          </li>
        </ul>
    </section>
    <section v-else-if="panel === 'cost'" class="card">
        <h3>{{ overviewRangeLabel(range) }}成本<small>按智能体<template v-if="range === '24h' || range === 'today'"> / 日预算</template></small></h3>
        <p v-if="!bars.length" class="empty">{{ overviewRangeLabel(range) }}还没有模型花费</p>
        <div v-else class="bars">
          <div v-for="row in bars" :key="row.agent">
            <div class="h"><AgentName :id="row.agent" /><span class="mono">{{ fmtMoney(row.costCny) }}<template v-if="row.dailyBudgetCny != null"> / {{ fmtMoney(row.dailyBudgetCny) }}</template></span></div>
            <div class="bar"><i :style="{ width: `${barWidth(row.costCny, row.dailyBudgetCny)}%`, background: barColor(row.costCny, row.dailyBudgetCny) }" /></div>
          </div>
        </div>
    </section>
    <section v-else>
      <div class="grid3">
        <div class="card"><h3>业务智能体</h3><div class="kv">
          <span title="已结束运行里，完成且没有转人工的占比。来源：agent_run">解决率</span><b>{{ layerPct(overview?.layers?.biz?.resolutionPct) }}</b>
          <span title="运行里转人工的占比。来源：agent_run">转人工率</span><b>{{ layerPct(overview?.layers?.biz?.handoffPct) }}</b>
          <span title="渠道反馈里点踩的占比。来源：channel_feedback">点踩率</span><b>{{ layerPct(overview?.layers?.biz?.downvotePct) }}</b>
          <span title="没有接地校验落库。">无依据回答率</span><b>{{ layerPct(overview?.layers?.biz?.ungroundedPct) }}</b>
        </div></div>
        <div class="card"><h3>研发智能体</h3><div class="kv">
          <span title="进入门禁或已结束的任务里，修复轮次为 0 且通过的占比。来源：devflow_job">首次门禁通过率</span><b>{{ layerPct(overview?.layers?.dev?.firstPassPct) }}</b>
          <span title="已结束任务里，完成且修复轮次小于 3 的占比。来源：devflow_job">3 轮内通过率</span><b>{{ layerPct(overview?.layers?.dev?.withinRoundsPct) }}</b>
          <span title="已完成任务从创建到更新的平均天数。来源：devflow_job">平均交付周期</span><b>{{ layerDays(overview?.layers?.dev?.avgDeliveryDays) }}</b>
          <span title="有接管人或状态为人工开发的占比。来源：devflow_job">人工接管率</span><b>{{ layerPct(overview?.layers?.dev?.takeoverPct) }}</b>
        </div></div>
        <div class="card"><h3>元智能体</h3><div class="kv">
          <span title="已完成的研发层新建 / 改造任务。来源：devflow_job">生产 / 升级研发员工</span><b>{{ layerPair(overview?.layers?.meta?.produced, overview?.layers?.meta?.upgraded) }}</b>
          <span title="研发员工最近一次完成评测的平均分。来源：eval_run">研发员工门禁均分</span><b>{{ layerScore(overview?.layers?.meta?.avgGateScore) }}</b>
          <span title="研发层代码产物里人工提交的占比。来源：devflow_artifact">人工补写 PR 占比</span><b>{{ layerPct(overview?.layers?.meta?.humanPrPct) }}</b>
        </div></div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.frac { font-size: 14px; font-weight: 500; }
.kpi .d a { color: var(--soft); text-decoration: none; }
.note { font-size: 11px; margin-top: 3px; }
.card { margin-bottom: 0; }
.pipe-note { margin: -6px 0 14px; font-size: 12px; color: var(--mute); }
.envtag {
  font-family: var(--mono);
  font-size: 11.5px;
  border: 1px solid var(--line2);
  border-radius: 7px;
  padding: 3px 9px;
  color: var(--ok);
}
.row2 .card { margin-bottom: 0; }
</style>
