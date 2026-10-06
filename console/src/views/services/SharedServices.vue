<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import { getSharedServices, type SharedServices } from '@/api/services'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { agentStatus, ago, fmtN, fmtPct } from '@/utils/format'
import { trendText } from '@/views/overview/overviewFormat'

type PageSize = 10 | 20 | 50 | 100

const STAGES: Record<string, { label: string; color: string }> = {
  rewrite: { label: '改写', color: '#5b9cf6' },
  vector: { label: '向量', color: '#2ec4b6' },
  keyword: { label: '关键词', color: '#8a97ab' },
  rerank: { label: '重排', color: '#ff7a45' },
  other: { label: '其他', color: '#4c5a70' },
}
const CALLER_COLORS: Record<string, string> = {
  'offshore-wind': '#34c38f', careermate: '#2ec4b6', 'cs-bot': '#b48cf2', askdb: '#5b9cf6', 'code-review': '#e36fae',
}

const RAG_CONSOLE = 'https://ragforge.net'

const envStore = useEnvStore()
const data = ref<SharedServices | null>(null)
const tab = ref<'health' | 'rag'>('health')
const loading = ref(false)
const healthPage = ref(1)
const healthSize = ref<PageSize>(10)
const kbPage = ref(1)
const kbSize = ref<PageSize>(10)

const rag = computed(() => data.value?.ragforge)
const services = computed(() => data.value?.services ?? [])
const knowledge = computed(() => rag.value?.knowledgeBases ?? [])
const healthRows = computed(() => services.value.slice((healthPage.value - 1) * healthSize.value, healthPage.value * healthSize.value))
const kbRows = computed(() => knowledge.value.slice((kbPage.value - 1) * kbSize.value, kbPage.value * kbSize.value))
const callers = computed(() => [...(rag.value?.callers ?? [])].sort((a, b) => (b.calls ?? 0) - (a.calls ?? 0)))
const stageMs = (stage: { p50Ms?: number; meanMs?: number }) => stage.p50Ms ?? stage.meanMs ?? 0
const stageTotal = computed(() => (rag.value?.stageLatency ?? []).reduce((n, s) => n + stageMs(s), 0) || 1)
const stageNote = computed(() => {
  const rows = rag.value?.stageLatency ?? []
  return rows.length > 0 && rows.every((s) => s.basis === 'mean' || (s.p50Ms == null && s.meanMs != null))
    ? '均值 · 进程内分段计时'
    : 'P50 · Langfuse retriever 子 span'
})
const rerankDominates = computed(() => {
  const rows = rag.value?.stageLatency ?? []
  const rerank = rows.find((s) => s.stage === 'rerank')
  if (!rerank) return false
  return rows.every((s) => stageMs(s) <= stageMs(rerank))
})
const callerMax = computed(() => Math.max(1, ...callers.value.map((c) => c.calls ?? 0)))
const trend = computed(() => trendText(rag.value?.kpi?.searchTrendPct))
const consoleUrl = computed(() => {
  const url = (data.value as { consoleUrl?: string } | null)?.consoleUrl
  return url && url.startsWith('http') ? url : RAG_CONSOLE
})
const costCaption = computed(() => ((rag.value?.kpi as { costSource?: string } | undefined)?.costSource === 'gateway' ? 'rag-forge 虚拟 Key' : 'rag-forge 计量'))

function seconds(value: number | null | undefined) {
  if (value == null || Number.isNaN(value)) return '—'
  return value >= 1 ? `${Number(value.toFixed(1))}s` : `${value.toFixed(2)}s`
}

function costText(value: number | null | undefined) {
  if (value == null || Number.isNaN(value)) return '—'
  return `¥${value.toFixed(2)}`
}

function recallText(value: number | null | undefined) {
  return value == null || Number.isNaN(value) ? '—' : String(value)
}

async function load() {
  loading.value = true
  try {
    data.value = await getSharedServices(envStore.env)
    healthPage.value = 1
    kbPage.value = 1
  } catch (error) {
    ElMessage.error(`加载共享服务失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

watch(() => envStore.env, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>共享服务</h2>
      <span class="sub">底座组件和被多个智能体共用的服务 · 在线状态来自 K8s 探测，指标来自 Prometheus</span>
      <span class="sp"></span>
      <a class="btn" :href="consoleUrl" target="_blank" rel="noopener">rag-forge 控制台 ↗</a>
    </div>
    <div class="chipsel" style="margin-bottom: 14px">
      <button :class="{ on: tab === 'health' }" @click="tab = 'health'">服务健康 · {{ data?.services?.length ?? 0 }}</button>
      <button :class="{ on: tab === 'rag' }" @click="tab = 'rag'">
        rag-forge 知识检索 · {{ rag?.knowledgeBases?.length ?? 0 }} 个知识库
      </button>
    </div>

    <div v-if="tab === 'health'" class="card">
      <h3>服务健康</h3>
      <table class="t">
        <thead><tr><th>服务</th><th>角色</th><th>实例</th><th>P95</th><th>错误率</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="s in healthRows" :key="s.name">
            <td class="nm"><b>{{ s.name }}</b></td>
            <td>{{ s.role }}</td>
            <td class="mono">{{ s.instances }}</td>
            <td class="mono">{{ s.p95 ?? '—' }}</td>
            <td class="mono">{{ s.errorRate ?? '—' }}</td>
            <td><StatusPill v-bind="agentStatus(s.status)" /></td>
          </tr>
        </tbody>
      </table>
      <Pager v-model:page="healthPage" v-model:size="healthSize" :total="services.length" />
    </div>

    <template v-else-if="rag">
      <div class="kpis">
        <div class="kpi"><div class="l">rag-forge 24h 检索</div><div class="v">{{ fmtN(rag.kpi?.searches24h) }}</div><div v-if="trend" class="d" :class="trend.tone">{{ trend.text }}</div><div v-else class="d">—</div></div>
        <div class="kpi"><div class="l">检索 P95</div><div class="v">{{ seconds(rag.kpi?.p95Seconds) }}</div><div class="d">P50 {{ seconds(rag.kpi?.p50Seconds) }}</div></div>
        <div class="kpi"><div class="l">限流 / 超时</div><div class="v" :style="rag.kpi?.throttleRate ? { color: 'var(--warn)' } : undefined">{{ fmtPct(rag.kpi?.throttleRate) }}</div><div class="d">{{ rag.kpi?.throttleRate == null ? '—' : '429 为主' }}</div></div>
        <div class="kpi"><div class="l">知识库</div><div class="v">{{ rag.kpi?.kbCount ?? '—' }}</div><div class="d">{{ rag.kpi?.staleKbCount ?? '—' }} 个超过 30 天未更新</div></div>
        <div class="kpi"><div class="l">今日模型成本</div><div class="v">{{ costText(rag.kpi?.modelCostCny) }}</div><div class="d">{{ costCaption }}</div></div>
      </div>
      <div class="row2e">
        <div class="card">
          <h3>检索分段耗时<small>{{ stageNote }}</small></h3>
          <div class="stack">
            <i v-for="s in rag.stageLatency" :key="s.stage" :style="{ width: `${(stageMs(s) / stageTotal) * 100}%`, background: (STAGES[s.stage!] ?? STAGES.other).color }" />
          </div>
          <div class="keys">
            <span v-for="s in rag.stageLatency" :key="s.stage"><i :style="{ background: (STAGES[s.stage!] ?? STAGES.other).color }" />{{ (STAGES[s.stage!] ?? STAGES.other).label }} {{ stageMs(s) }}ms</span>
          </div>
          <p v-if="rerankDominates" class="mut" style="font-size: 12px; margin-top: 10px">重排占比最高；gte-rerank 自部署，扩副本即可。</p>
        </div>
        <div class="card">
          <h3>按调用方</h3>
          <p v-if="!callers.length" class="empty">这个环境还没有带来源标记的检索</p>
          <div v-else class="bars">
            <div v-for="c in callers" :key="c.agent">
              <div class="h"><span>{{ c.agent }}</span><span class="mono">{{ fmtN(c.calls) }}</span></div>
              <div class="bar"><i :style="{ width: `${((c.calls ?? 0) / callerMax) * 100}%`, background: CALLER_COLORS[c.agent!] ?? 'var(--soft)' }" /></div>
            </div>
          </div>
        </div>
      </div>
      <div class="card">
        <h3>知识库<small>recall@5 来自 rag-forge 检索评测台</small></h3>
        <table class="t">
          <thead><tr><th>知识库</th><th>所属</th><th>文档</th><th>最近更新</th><th>24h 检索</th><th>零结果率</th><th>recall@5</th></tr></thead>
          <tbody>
            <tr v-for="kb in kbRows" :key="kb.kb">
              <td class="mono">{{ kb.kb }}</td>
              <td>{{ kb.owner }}</td>
              <td class="mono">{{ fmtN(kb.documents) }}</td>
              <td class="mono" :style="kb.stale ? { color: 'var(--warn)' } : undefined">{{ ago(kb.updatedAt) }}</td>
              <td class="mono">{{ fmtN(kb.searches24h) }}</td>
              <td class="mono" :style="kb.stale ? { color: 'var(--warn)' } : undefined">{{ fmtPct(kb.zeroHitRate) }}</td>
              <td class="mono" :style="kb.recallAt5 != null && kb.recallAt5 < 0.85 ? { color: 'var(--warn)' } : undefined">{{ recallText(kb.recallAt5) }}</td>
            </tr>
          </tbody>
        </table>
        <Pager v-model:page="kbPage" v-model:size="kbSize" :total="knowledge.length" />
      </div>
    </template>
  </div>
</template>
