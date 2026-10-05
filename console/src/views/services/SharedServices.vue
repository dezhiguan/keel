<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import { getSharedServices, type SharedServices } from '@/api/services'
import { toKeelError } from '@/api/http'
import { agentStatus, ago, fmtN, fmtPct } from '@/utils/format'

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

const data = ref<SharedServices | null>(null)
const tab = ref<'health' | 'rag'>('health')
const loading = ref(false)

const rag = computed(() => data.value?.ragforge)
const stageMs = (stage: { p50Ms?: number; meanMs?: number }) => stage.p50Ms ?? stage.meanMs ?? 0
const stageTotal = computed(() => (rag.value?.stageLatency ?? []).reduce((n, s) => n + stageMs(s), 0) || 1)
const stageNote = computed(() => {
  const rows = rag.value?.stageLatency ?? []
  return rows.length > 0 && rows.every((s) => s.basis === 'mean' || (s.p50Ms == null && s.meanMs != null))
    ? '均值 · 进程内分段计时'
    : 'P50 · Langfuse retriever 子 span'
})
const callerMax = computed(() => Math.max(1, ...(rag.value?.callers ?? []).map((c) => c.calls ?? 0)))
const seconds = (value: number | null | undefined) => (value == null ? '—' : `${value}s`)
const costText = computed(() => {
  const cost = rag.value?.kpi?.modelCostCny
  return cost == null ? '—' : `¥${cost.toFixed(2)}`
})

onMounted(async () => {
  loading.value = true
  try {
    data.value = await getSharedServices()
  } catch (error) {
    ElMessage.error(`加载共享服务失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>共享服务</h2>
      <span class="sub">底座组件和被多个智能体共用的服务 · 在线状态来自 K8s 探测，指标来自 Prometheus</span>
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
          <tr v-for="s in data?.services ?? []" :key="s.name">
            <td class="nm"><b>{{ s.name }}</b></td>
            <td>{{ s.role }}</td>
            <td class="mono">{{ s.instances }}</td>
            <td class="mono">{{ s.p95 ?? '—' }}</td>
            <td class="mono">{{ s.errorRate ?? '—' }}</td>
            <td><StatusPill v-bind="agentStatus(s.status)" /></td>
          </tr>
        </tbody>
      </table>
    </div>

    <template v-else-if="rag">
      <div class="kpis">
        <div class="kpi"><div class="l">rag-forge 24h 检索</div><div class="v">{{ fmtN(rag.kpi?.searches24h) }}</div><div class="d up">{{ rag.kpi?.searchTrendPct == null ? '—' : `▲ ${rag.kpi.searchTrendPct}%` }}</div></div>
        <div class="kpi"><div class="l">检索 P95</div><div class="v">{{ seconds(rag.kpi?.p95Seconds) }}</div><div class="d">P50 {{ seconds(rag.kpi?.p50Seconds) }}</div></div>
        <div class="kpi"><div class="l">限流 / 超时</div><div class="v" style="color: var(--warn)">{{ fmtPct(rag.kpi?.throttleRate) }}</div><div class="d">429 为主</div></div>
        <div class="kpi"><div class="l">知识库</div><div class="v">{{ rag.kpi?.kbCount ?? '—' }}</div><div class="d">{{ rag.kpi?.staleKbCount ?? '—' }} 个超过 30 天未更新</div></div>
        <div class="kpi"><div class="l">今日模型成本</div><div class="v">{{ costText }}</div><div class="d">rag-forge 计量</div></div>
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
        </div>
        <div class="card">
          <h3>按调用方</h3>
          <div class="bars">
            <div v-for="c in rag.callers" :key="c.agent">
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
            <tr v-for="kb in rag.knowledgeBases" :key="kb.kb" :style="kb.stale ? { color: 'var(--warn)' } : undefined">
              <td class="mono">{{ kb.kb }}</td>
              <td>{{ kb.owner }}</td>
              <td class="mono">{{ fmtN(kb.documents) }}</td>
              <td class="mono">{{ ago(kb.updatedAt) }}</td>
              <td class="mono">{{ fmtN(kb.searches24h) }}</td>
              <td class="mono">{{ fmtPct(kb.zeroHitRate) }}</td>
              <td class="mono">{{ kb.recallAt5 }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
