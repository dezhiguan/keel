<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AgentName from '@/components/AgentName.vue'
import { listAgents } from '@/api/agents'
import { addQualityCase, getQuality, type QualityReport } from '@/api/quality'
import { toKeelError } from '@/api/http'

const agent = ref('')
const agents = ref<{ name: string; displayName: string }[]>([])
const report = ref<QualityReport | null>(null)
const loading = ref(false)
const adding = ref('')

const rateText = computed(() => {
  const rate = report.value?.downRate
  if (!report.value?.connected) return '未接入'
  if (rate == null) return '—'
  return `${(rate * 100).toFixed(1)}%`
})

async function load() {
  loading.value = true
  try {
    report.value = await getQuality(agent.value || undefined)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    loading.value = false
  }
}

async function add(traceId: string, owner: string) {
  adding.value = traceId
  try {
    await addQualityCase(owner, traceId)
    ElMessage.success('已加入评测集，下次回归会包含这条')
    await load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    adding.value = ''
  }
}

onMounted(async () => {
  try {
    const page = await listAgents({ page: 1, size: 100 })
    agents.value = (page.items ?? []).flatMap((item) => item.name ? [{ name: item.name, displayName: item.displayName || item.name }] : [])
  } catch {
    agents.value = []
  }
  await load()
})

watch(agent, () => { void load() })
</script>

<template>
  <div class="vh">
    <h2>质量中心</h2>
    <span class="sub">用户反馈来自 Langfuse 分数 user-feedback；点踩写入该智能体的评测集</span>
    <span class="sp" />
    <select v-model="agent" class="sel">
      <option value="">全部智能体</option>
      <option v-for="item in agents" :key="item.name" :value="item.name">{{ item.displayName }} · {{ item.name }}</option>
    </select>
  </div>
  <div v-if="report && !report.connected" class="banner warn">Langfuse 未接入，反馈和点踩率显示为未接入。</div>
  <div class="kpis">
    <div class="kpi"><div class="l">反馈数</div><div class="v">{{ report?.connected ? report.feedbackCount : '未接入' }}</div></div>
    <div class="kpi"><div class="l">点踩数</div><div class="v">{{ report?.connected ? report.downCount : '未接入' }}</div></div>
    <div class="kpi"><div class="l">点踩率</div><div class="v">{{ rateText }}</div></div>
  </div>
  <div class="card" v-loading="loading">
    <table class="t">
      <thead>
        <tr><th>智能体</th><th>输入</th><th>回复</th><th>说明</th><th>时间</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="row in report?.cases ?? []" :key="row.traceId">
          <td><AgentName :id="row.agent" /></td>
          <td>{{ row.input || '—' }}</td>
          <td>{{ row.output || '—' }}</td>
          <td>{{ row.comment || '—' }}</td>
          <td class="mono">{{ row.createdAt || '—' }}</td>
          <td>
            <button v-if="row.added" class="btn" type="button" disabled>已加入</button>
            <button v-else class="btn pri" type="button" :disabled="adding === row.traceId" @click="add(row.traceId, row.agent)">加入评测集</button>
          </td>
        </tr>
        <tr v-if="!report?.cases?.length"><td colspan="6" class="empty">没有点踩记录</td></tr>
      </tbody>
    </table>
  </div>
</template>
