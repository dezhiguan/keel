<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import KpiCard from '@/components/KpiCard.vue'
import StatusPill from '@/components/StatusPill.vue'
import { getOverview, type Overview } from '@/api/overview'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { agentStatus, orDash } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const envStore = useEnvStore()
const overview = ref<Overview | null>(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    overview.value = await getOverview({ env: envStore.env, range: '24h' })
  } catch (error) {
    ElMessage.error(`加载总览失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

watch(() => envStore.env, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="kpis">
      <KpiCard
        label="在线智能体"
        :value="`${orDash(overview?.kpi?.onlineAgents)} / ${orDash(overview?.kpi?.totalAgents)}`"
      />
      <KpiCard label="24h 调用量" :value="orDash(overview?.kpi?.calls)" />
      <KpiCard label="模型成本（¥）" :value="orDash(overview?.kpi?.modelCostCny)" />
      <KpiCard label="平均评分" :value="orDash(overview?.kpi?.avgScore)" />
      <KpiCard label="待审批" :value="orDash(overview?.kpi?.pendingApprovals)" />
    </div>

    <section class="panel">
      <h3>智能体健康</h3>
      <table class="t">
        <thead><tr><th>智能体</th><th>状态</th><th>负责组织</th><th>24h 调用</th><th>评分</th></tr></thead>
        <tbody>
          <tr v-for="a in overview?.agents ?? []" :key="a.name" class="click" @click="a.name && router.push({ query: { ...route.query, drawer: a.name } })">
            <td class="nm"><b>{{ a.displayName }}</b><small class="mono">{{ a.name }}</small></td>
            <td><StatusPill v-bind="agentStatus(a.status)" /></td>
            <td>{{ a.ownerOrg }}</td>
            <td class="mono">{{ orDash(a.calls24h) }}</td>
            <td class="mono">{{ orDash(a.score) }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="panel">
      <h3>告警</h3>
      <el-empty v-if="!overview?.alerts?.length" description="暂无告警" :image-size="60" />
      <ul v-else>
        <li v-for="(alert, i) in overview.alerts" :key="i">{{ alert.text }}</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.kpis { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; margin-bottom: 16px; }
.panel {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 16px;
}
h3 { margin: 0 0 12px; color: var(--white); font-size: 14px; font-weight: 500; }
</style>
