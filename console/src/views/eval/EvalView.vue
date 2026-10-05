<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import { getEvalRun, getLatestEval, runEval, type EvalResult } from '@/api/eval'
import { listAgents } from '@/api/agents'
import { toKeelError } from '@/api/http'
import type { StatusTone } from '@/utils/format'

const VERDICT: Record<string, { label: string; tone: StatusTone }> = {
  IMPROVED: { label: '提升', tone: 'ok' },
  TOLERATED: { label: '容忍', tone: 'ok' },
  EXCEEDED: { label: '超出阈值', tone: 'failed' },
}

const agent = ref('')
const agentOptions = ref<string[]>([])
const result = ref<EvalResult | null>(null)
const loading = ref(false)
const progress = ref<number | null>(null)
let timer: ReturnType<typeof setInterval> | undefined
let polling = false

async function load() {
  loading.value = true
  try {
    result.value = await getLatestEval(agent.value)
  } catch (error) {
    result.value = null
    const keel = toKeelError(error)
    if (keel.code !== 'SERVER_NOT_FOUND') ElMessage.error(`加载评测结果失败：${keel.message}`)
  } finally {
    loading.value = false
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
  // TODO(P2-1): marking an expected regression needs a keel-server endpoint; not in console-api.openapi.yaml yet.
  ElMessage.info('「标记为预期变化」尚未接入')
}

onMounted(async () => {
  try {
    const page = await listAgents({ size: 100 })
    const names = (page.items ?? []).filter((a) => a.status !== 'DRAFT' && a.name).map((a) => a.name!)
    agentOptions.value = names
    if (names.length && !names.includes(agent.value)) agent.value = names[0]
  } catch (error) {
    ElMessage.error(`加载智能体失败：${toKeelError(error).message}`)
  }
})
onBeforeUnmount(() => clearInterval(timer))
watch(agent, () => {
  if (agent.value) load()
})
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>评测中心</h2>
      <span class="sub">Langfuse 数据集 {{ result?.dataset ?? '—' }} · 门禁由 keel-gate 判定</span>
      <span class="sp" />
      <select v-model="agent" class="inp">
        <option v-for="a in agentOptions" :key="a" :value="a">{{ a }}</option>
      </select>
      <button class="btn pri" :disabled="!agent || progress !== null" @click="run">▶ 运行回归</button>
    </div>

    <template v-if="result">
      <div class="gate" :class="{ pass: result.passed }">
        <div class="big">{{ result.scoreTotal }}</div>
        <div>
          <b>{{ result.passed ? '门禁通过，可以发布' : '门禁未通过，发布到 prod 已被阻止' }}</b><br />
          <span class="mut">
            规则：总分 ≥ {{ result.gate?.minScore }}，且任一维度退步不超过 {{ result.gate?.maxRegression }}pt。{{ result.reason ?? '' }}
          </span>
        </div>
        <span class="sp" />
        <button v-if="!result.passed" class="btn" @click="markExpected">标记为预期变化</button>
      </div>
      <div v-if="progress !== null" class="prog"><i :style="{ width: `${progress * 100}%` }" /></div>

      <div class="card">
        <h3>分维度对比<small>prod {{ result.prodVersion }} vs 候选 {{ result.candidateVersion }}</small></h3>
        <table class="t">
          <thead><tr><th>维度（用例标签）</th><th>用例数</th><th>prod</th><th>候选</th><th>变化</th><th>判定</th></tr></thead>
          <tbody>
            <tr v-for="d in result.dimensions ?? []" :key="d.tag">
              <td>{{ d.tag }}</td>
              <td class="mono">{{ d.cases ?? '—' }}</td>
              <td class="mono">{{ d.prodScore == null ? '—' : d.prodScore.toFixed(2) }}</td>
              <td class="mono">{{ d.candidateScore == null ? '—' : d.candidateScore.toFixed(2) }}</td>
              <td class="mono" :class="(d.deltaPt ?? 0) < 0 ? 'down' : 'up'">{{ d.deltaPt == null ? '—' : `${d.deltaPt > 0 ? '+' : ''}${d.deltaPt}pt` }}</td>
              <td><StatusPill v-bind="VERDICT[d.verdict ?? 'TOLERATED']" /></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <div v-else-if="!loading" class="card empty">{{ agent ? '该智能体还没有评测记录' : '还没有可评测的智能体' }}</div>
  </div>
</template>
