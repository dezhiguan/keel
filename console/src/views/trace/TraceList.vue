<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import { listAgents } from '@/api/agents'
import { listDevflowJobs } from '@/api/devflow'
import { listTraces, type ListTracesQuery, type TracePage } from '@/api/traces'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { ago, fmtCny, fmtMs, nodeStatus } from '@/utils/format'
import { avatarColor } from '@/views/agents/agentDrawer'
import { agentChain, humanWait, showTokens, sourceText, traceJobPlaceholders, traceWindow, userLine, type TraceRange } from './traceView'

const RANGES: [TraceRange, string][] = [['1h', '近 1 小时'], ['24h', '近 24 小时'], ['7d', '近 7 天']]
const STATUSES: [ListTracesQuery['status'] | undefined, string][] = [
  [undefined, '全部状态'],
  ['ok', 'ok'],
  ['fallback', '降级'],
  ['failed', '失败'],
  ['blocked', '已拦截'],
  ['pending', '挂起中'],
]

const router = useRouter()
const envStore = useEnvStore()
const agents = ref<string[]>([])
const jobs = ref<{ jobId: string; title?: string }[]>([])
const result = ref<TracePage | null>(null)
const loading = ref(false)
const filter = reactive<{
  agent: string
  jobId: string
  status?: ListTracesQuery['status']
  range: TraceRange
  multi: boolean
  page: number
  size: NonNullable<ListTracesQuery['size']>
}>({
  agent: '',
  jobId: '',
  status: undefined,
  range: '24h',
  multi: false,
  page: 1,
  size: 10,
})

const failed = ref(false)
const rows = computed(() => {
  const items = result.value?.items ?? []
  if (!filter.jobId) return items
  return items.filter((item) => item.devflowJobId === filter.jobId)
})

const placeholders = computed(() => {
  if (!filter.jobId || loading.value || rows.value.length) return []
  return traceJobPlaceholders(filter.jobId)
})

async function loadAgents() {
  try {
    const page = await listAgents({ env: envStore.env, page: 1, size: 100 })
    agents.value = (page.items ?? []).map((item) => item.name).filter((name): name is string => !!name)
  } catch {
    agents.value = []
  }
}

async function loadJobs() {
  try {
    const page = await listDevflowJobs()
    const items = (page.items ?? []).filter((job) => !!job.jobId).map((job) => ({ jobId: job.jobId!, title: job.title }))
    if (items.length) {
      jobs.value = items
      return
    }
  } catch {
    // 研发任务接口还没接上时，筛选项用原型里的任务号。
  }
  jobs.value = [{ jobId: 'DF-0019', title: '发布说明生成' }]
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    const window = traceWindow(filter.range)
    result.value = await listTraces({
      env: envStore.env,
      agent: filter.agent || undefined,
      jobId: filter.jobId || undefined,
      status: filter.status,
      multi: filter.multi || undefined,
      from: window.from,
      to: window.to,
      page: filter.page,
      size: filter.size,
    })
  } catch (error) {
    failed.value = true
    ElMessage.error(toKeelError(error).message || '链路暂时拉不到，请稍后再试')
  } finally {
    loading.value = false
  }
}

function search() {
  if (filter.page === 1) load()
  else filter.page = 1
}

function setStatus(status?: ListTracesQuery['status']) {
  filter.status = status
  search()
}

function setRange(range: TraceRange) {
  filter.range = range
  search()
}

function clearJob() {
  filter.jobId = ''
  search()
}

watch(() => envStore.env, () => { loadAgents(); loadJobs(); search() })
watch(() => [filter.page, filter.size], load, { immediate: true })
loadAgents()
loadJobs()
</script>

<template>
  <div>
    <div class="vh">
      <h2>链路追踪</h2>
      <span class="sub">数据来自 Langfuse，叠加 Keel 的审计、审批、门禁信息</span>
      <span class="sp" />
      <a v-if="result?.langfuseUrl" class="btn" :href="result.langfuseUrl" target="_blank" rel="noopener">在 Langfuse 中打开 ↗</a>
    </div>
    <div class="toolbar">
      <select v-model="filter.agent" class="inp" @change="search">
        <option value="">全部智能体</option>
        <option v-for="a in agents" :key="a" :value="a">{{ a }}</option>
      </select>
      <select v-model="filter.jobId" class="inp" @change="search">
        <option value="">全部研发任务</option>
        <option v-for="job in jobs" :key="job.jobId" :value="job.jobId">{{ job.jobId }}{{ job.title ? ` · ${job.title}` : '' }}</option>
      </select>
      <span v-if="filter.jobId" class="chip" style="border-color: #5a3420">
        研发任务 <b>{{ filter.jobId }}</b>
        <button type="button" aria-label="清除研发任务筛选" style="margin-left: 4px; border: 0; background: none; color: inherit; cursor: pointer" @click="clearJob">×</button>
      </span>
      <div class="chipsel">
        <button v-for="[value, label] in STATUSES" :key="label" :class="{ on: filter.status === value }" @click="setStatus(value)">{{ label }}</button>
      </div>
      <div class="chipsel">
        <button v-for="[value, label] in RANGES" :key="value" :class="{ on: filter.range === value }" @click="setRange(value)">{{ label }}</button>
      </div>
      <div class="chipsel">
        <button :class="{ on: filter.multi }" @click="filter.multi = !filter.multi; search()">只看多智能体</button>
      </div>
    </div>

    <div v-loading="loading" class="card">
      <h3>调用记录<small>一行是一次用户调用（一个 trace），点击查看链路详情</small></h3>
      <p v-if="failed" class="empty" style="padding: 12px 0">
        链路暂时拉不到，请重试
        <button type="button" class="btn sm" style="margin-left: 8px" @click="load">重试</button>
      </p>
      <table class="t">
        <thead><tr><th>问题</th><th>智能体</th><th>来源</th><th>环境</th><th>开始</th><th>耗时</th><th>tokens</th><th>成本</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="t in rows" :key="t.traceId" class="click" @click="router.push(`/traces/${t.traceId}`)">
            <td class="nm" style="max-width: 420px">
              <b v-if="t.question" style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{{ t.question }}</b>
              <small class="mono">{{ t.traceId }}<template v-if="userLine(t.userId, t.userRole)"> · {{ userLine(t.userId, t.userRole) }}</template></small>
            </td>
            <td>
              <template v-if="t.multiAgent && (t.agents?.length ?? 0) > 1">
                <span class="pill nd p-acc">多智能体 · {{ t.agents?.length }}</span>
                <div class="mut" style="font-size: 11px; margin-top: 2px">{{ agentChain(t.agents) }}</div>
              </template>
              <template v-else-if="t.rootAgent">
                <span class="dot" :style="{ background: avatarColor(t.rootAgent) }" /> {{ t.rootAgent }}
              </template>
              <template v-else>—</template>
            </td>
            <td @click.stop>
              <RouterLink v-if="t.devflowJobId" :to="`/jobs/${t.devflowJobId}`">{{ sourceText(t.devflowJobId, t.devflowLabel) }}</RouterLink>
              <span v-else class="mut">—</span>
            </td>
            <td>{{ t.env || '—' }}</td>
            <td class="mono">{{ ago(t.startedAt) }}</td>
            <td class="mono">
              {{ fmtMs(t.durationMs) }}
              <div v-if="t.humanWaitMs" class="mut" style="font-size: 11px">+ 人工 {{ humanWait(t.humanWaitMs) }}</div>
            </td>
            <td class="mono">{{ showTokens(t.tokens) }}</td>
            <td class="mono">{{ fmtCny(t.costCny) }}</td>
            <td>
              <span v-if="t.blocked" class="pill p-bad">已拦截</span>
              <template v-else-if="t.pendingReason">
                <span class="pill p-warn">挂起中</span>
                <div class="mut" style="font-size: 11px">{{ t.pendingReason }}</div>
              </template>
              <template v-else>
                <StatusPill v-bind="nodeStatus(t.status)" />
                <span v-if="t.cached" class="pill nd p-soft" style="margin-left: 4px">缓存</span>
              </template>
            </td>
          </tr>
          <tr v-for="row in placeholders" :key="row.traceId">
            <td class="nm"><b class="mut">占位</b><small class="mono">{{ row.traceId }}</small></td>
            <td><span class="dot" :style="{ background: avatarColor(row.rootAgent) }" /> {{ row.rootAgent }}</td>
            <td>
              <RouterLink :to="`/jobs/${row.jobId}`">{{ sourceText(row.jobId, row.label) }}</RouterLink>
            </td>
            <td class="mut">—</td>
            <td class="mono">—</td>
            <td class="mono">{{ row.duration }}</td>
            <td class="mono">—</td>
            <td class="mono">{{ row.cost }}</td>
            <td><span class="pill nd p-soft">占位</span></td>
          </tr>
          <tr v-if="!rows.length && !placeholders.length && !loading && !failed"><td colspan="9" class="empty">没有符合条件的链路</td></tr>
        </tbody>
      </table>
      <Pager v-model:page="filter.page" v-model:size="filter.size" :total="filter.jobId && !rows.length ? 0 : (result?.total ?? 0)" />
      <div class="mut" style="font-size: 11.5px; margin-top: 8px">按 <span class="mono">keel.devflow.job_id</span> 过滤，详情页沿用现有三视图。</div>
      <div class="srcnote">列表由 keel-server 读 Langfuse Observations API v2 的根节点摘要，用量来自生成节点的 usage，不拉全文。智能体、状态、时间筛选和分页都在服务端完成；每行只带摘要，不带节点。点开一行才请求一次详情。</div>
    </div>
  </div>
</template>
