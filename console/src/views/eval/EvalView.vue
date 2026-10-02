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

const agent = ref('offshore-wind')
const agentOptions = ref<string[]>(['offshore-wind'])
const result = ref<EvalResult | null>(null)
const loading = ref(false)
const progress = ref<number | null>(null)
let timer: ReturnType<typeof setInterval> | undefined

async function load() {
  loading.value = true
  try {
    result.value = await getLatestEval(agent.value)
  } catch (error) {
    result.value = null
    ElMessage.error(`加载评测结果失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

async function run() {
  try {
    const { runId } = await runEval(agent.value)
    progress.value = 0
    timer = setInterval(async () => {
      const r = await getEvalRun(runId!)
      progress.value = r.progress ?? 0
      if (r.state !== 'RUNNING') {
        clearInterval(timer)
        progress.value = null
        if (r.result) result.value = r.result
        ElMessage[r.result?.passed ? 'success' : 'warning'](`回归完成：总分 ${r.result?.scoreTotal}`)
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
    const names = (page.items ?? []).filter((a) => a.status !== 'DRAFT').map((a) => a.name!)
    if (names.length) agentOptions.value = names
  } catch {
    // keel-server unavailable: keep the default option so the page still renders.
  }
})
onBeforeUnmount(() => clearInterval(timer))
watch(agent, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>评测中心</h2>
      <span class="sub">Langfuse 数据集 {{ result?.dataset ?? '—' }} · 门禁由 keel-gate 判定</span>
      <span class="sp" />
      <el-select v-model="agent" style="width: 180px">
        <el-option v-for="a in agentOptions" :key="a" :label="a" :value="a" />
      </el-select>
      <button class="btn pri" :disabled="progress !== null" @click="run">▶ 运行回归</button>
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
            <tr v-for="d in result.dimensions" :key="d.tag">
              <td>{{ d.tag }}</td>
              <td class="mono">{{ d.cases }}</td>
              <td class="mono">{{ d.prodScore?.toFixed(2) }}</td>
              <td class="mono">{{ d.candidateScore?.toFixed(2) }}</td>
              <td class="mono" :class="(d.deltaPt ?? 0) < 0 ? 'down' : 'up'">{{ (d.deltaPt ?? 0) > 0 ? '+' : '' }}{{ d.deltaPt }}pt</td>
              <td><StatusPill v-bind="VERDICT[d.verdict ?? 'TOLERATED']" /></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <div v-else-if="!loading" class="card empty">该智能体还没有评测记录</div>
  </div>
</template>
