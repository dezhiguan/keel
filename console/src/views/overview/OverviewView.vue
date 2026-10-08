<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import { listDevflowJobs } from '@/api/devflow'
import { getOverview, type Overview } from '@/api/overview'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { agentStatus, fmtN } from '@/utils/format'
import { ALERT_KIND, fmtMoney, fmtScore, fmtSeconds, hm, pipelineCards, pipelineMoney, techLabel, trendText, type PipelineCards } from './overviewFormat'

type Category = 'all' | 'biz' | 'dev'

const CATEGORIES: [Category, string][] = [['all', '全部'], ['biz', '业务智能体'], ['dev', '研发智能体']]

const route = useRoute()
const router = useRouter()
const envStore = useEnvStore()
const overview = ref<Overview | null>(null)
const pipeline = ref<PipelineCards>(pipelineCards(null))
const loading = ref(false)
const category = ref<Category>('all')
const page = ref(1)
const size = ref<10 | 20 | 50 | 100>(10)

const envLabel = computed(() => (envStore.env === 'all' ? '全部环境' : envStore.env))
const kpi = computed(() => overview.value?.kpi)
const gate = computed(() => kpi.value?.gateThreshold ?? 0.85)
const trend = computed(() => trendText(kpi.value?.callsTrendPct))
const uncategorized = computed(() => {
  const total = kpi.value?.totalAgents ?? 0
  const known = (kpi.value?.bizCount ?? 0) + (kpi.value?.devCount ?? 0)
  return Math.max(0, total - known)
})
const filtered = computed(() => (overview.value?.agents ?? []).filter((agent) => category.value === 'all' || agent.category === category.value))
const rows = computed(() => filtered.value.slice((page.value - 1) * size.value, page.value * size.value))
const bars = computed(() =>
  [...(overview.value?.costByAgent ?? [])]
    .filter((row) => (row.costCny ?? 0) > 0)
    .sort((a, b) => (b.costCny ?? 0) - (a.costCny ?? 0))
    .slice(0, 6),
)

async function load() {
  loading.value = true
  const [overviewResult, jobsResult] = await Promise.allSettled([
    getOverview({ env: envStore.env, range: '24h' }),
    listDevflowJobs(),
  ])
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
  if (!budget || budget <= 0) return 0
  return Math.min(100, ((cost ?? 0) / budget) * 100)
}

function barColor(cost?: number, budget?: number) {
  const pct = barWidth(cost, budget)
  if (pct > 80) return 'var(--bad)'
  if (pct > 60) return 'var(--warn)'
  return 'var(--teal)'
}

watch(() => envStore.env, () => {
  page.value = 1
  load()
}, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>总览</h2>
      <span class="sub">{{ envLabel }} · 近 24 小时</span>
      <span class="sp" />
      <span class="envtag">研发任务产出只进 staging · 上生产需人批</span>
      <button v-write class="btn pri" @click="router.push('/agents/new')">+ 新建智能体</button>
    </div>

    <div class="kpis">
      <div class="kpi">
        <div class="l">在线智能体</div>
        <div class="v">{{ kpi?.onlineAgents ?? '—' }}<span class="mut frac"> / {{ kpi?.totalAgents ?? '—' }}</span></div>
        <div class="d">业务 {{ kpi?.bizCount ?? 0 }} · 研发 {{ kpi?.devCount ?? 0 }}<template v-if="uncategorized"> · 未分类 {{ uncategorized }}</template></div>
      </div>
      <div class="kpi">
        <div class="l">调用次数</div>
        <div class="v">{{ fmtN(kpi?.calls) }}</div>
        <div v-if="trend" class="d" :class="trend.tone">{{ trend.text }}</div>
        <div v-else class="d">近 24 小时</div>
      </div>
      <div class="kpi">
        <div class="l">模型成本</div>
        <div class="v">{{ fmtMoney(kpi?.modelCostCny) }}</div>
        <div class="d">经薄网关统计</div>
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

    <section class="card pipe-alerts">
      <h3>告警<small>新增一类：研发任务</small></h3>
      <p v-if="!pipeline.alerts.length" class="empty">{{ pipeline.active == null ? '账本接入后显示研发任务告警' : '暂无研发任务告警' }}</p>
      <ul v-else class="alerts">
        <li v-for="alert in pipeline.alerts" :key="`${alert.jobId}-${alert.text}`">
          <span class="tm">{{ alert.at }}</span>
          <span class="pill nd p-warn">研发任务</span>
          <RouterLink :to="`/jobs/${alert.jobId}`">{{ alert.text }}</RouterLink>
        </li>
      </ul>
    </section>

    <section class="card">
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
            <td class="nm"><b>{{ agent.displayName }}</b><small class="mono">{{ agent.name }}</small></td>
            <td>
              <span v-if="agent.category === 'biz'" class="pill nd p-teal">业务</span>
              <span v-else-if="agent.category === 'dev'" class="pill nd p-acc">研发</span>
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

    <div class="row2">
      <section class="card">
        <h3>告警与事件</h3>
        <p v-if="!overview?.alerts?.length" class="empty">暂无告警</p>
        <ul v-else class="alerts">
          <li v-for="(alert, index) in overview.alerts" :key="`${alert.agent}-${alert.kind}-${index}`">
            <span class="tm">{{ hm(alert.at) }}</span>
            <span class="pill nd" :class="ALERT_KIND[alert.kind || '']?.[1] || 'p-mute'">{{ ALERT_KIND[alert.kind || '']?.[0] || alert.level }}</span>
            <span>{{ alert.text }}</span>
          </li>
        </ul>
      </section>
      <section class="card">
        <h3>今日成本<small>按智能体 / 日预算</small></h3>
        <p v-if="!bars.length" class="empty">今日还没有模型花费</p>
        <div v-else class="bars">
          <div v-for="row in bars" :key="row.agent">
            <div class="h"><span>{{ row.agent }}</span><span class="mono">{{ fmtMoney(row.costCny) }}<template v-if="row.dailyBudgetCny != null"> / {{ fmtMoney(row.dailyBudgetCny) }}</template></span></div>
            <div class="bar"><i :style="{ width: `${barWidth(row.costCny, row.dailyBudgetCny)}%`, background: barColor(row.costCny, row.dailyBudgetCny) }" /></div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.frac { font-size: 14px; font-weight: 500; }
.kpi .d a { color: var(--soft); text-decoration: none; }
.note { font-size: 11px; margin-top: 3px; }
.card { margin-bottom: 0; }
.pipe-alerts { margin-bottom: 14px; }
.pipe-alerts .empty { padding: 8px 0; text-align: left; }
.pipe-note { margin: -6px 0 14px; font-size: 12px; color: var(--mute); }
.alerts a { color: var(--soft); text-decoration: none; }
.alerts a:hover { text-decoration: underline; }
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
