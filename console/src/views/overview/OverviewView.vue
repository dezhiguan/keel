<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import KpiCard from '@/components/KpiCard.vue'
import StatusPill from '@/components/StatusPill.vue'
import { getOverview, type Overview } from '@/api/overview'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { agentStatus, orDash } from '@/utils/format'

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
      <el-table :data="overview?.agents ?? []" size="small">
        <el-table-column prop="name" label="智能体" min-width="140" />
        <el-table-column prop="displayName" label="名称" min-width="120" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusPill v-bind="agentStatus(row.status)" />
          </template>
        </el-table-column>
        <el-table-column prop="ownerOrg" label="负责组织" min-width="120" />
        <el-table-column label="24h 调用" width="100">
          <template #default="{ row }">{{ orDash(row.calls24h) }}</template>
        </el-table-column>
        <el-table-column label="评分" width="80">
          <template #default="{ row }">{{ orDash(row.score) }}</template>
        </el-table-column>
      </el-table>
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
