<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import { listAgents, type AgentPage, type AgentStatus, type ListAgentsQuery } from '@/api/agents'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { agentStatus, orDash } from '@/utils/format'

const STATUSES: AgentStatus[] = ['DRAFT', 'REGISTERED', 'ONLINE', 'DEGRADED', 'OFFLINE', 'RETIRED']

const envStore = useEnvStore()
const result = ref<AgentPage | null>(null)
const loading = ref(false)
const filter = reactive<{ status?: AgentStatus; q: string; page: number; size: NonNullable<ListAgentsQuery['size']> }>({
  status: undefined,
  q: '',
  page: 1,
  size: 10,
})

async function load() {
  loading.value = true
  try {
    result.value = await listAgents({
      env: envStore.env,
      status: filter.status,
      q: filter.q || undefined,
      page: filter.page,
      size: filter.size,
    })
  } catch (error) {
    ElMessage.error(`加载智能体失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

function search() {
  filter.page = 1
  load()
}

watch(() => envStore.env, search)
watch(() => [filter.page, filter.size], load, { immediate: true })
</script>

<template>
  <section class="panel">
    <div class="bar">
      <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 140px" @change="search">
        <el-option v-for="s in STATUSES" :key="s" :label="agentStatus(s).label" :value="s" />
      </el-select>
      <el-input
        v-model="filter.q"
        placeholder="按 id、名称、负责人搜索"
        clearable
        style="width: 260px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-button type="primary" @click="search">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="result?.items ?? []" size="small">
      <el-table-column prop="name" label="智能体" min-width="140" />
      <el-table-column prop="displayName" label="名称" min-width="120" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <StatusPill v-bind="agentStatus(row.status)" />
        </template>
      </el-table-column>
      <el-table-column label="语言" width="90">
        <template #default="{ row }">{{ orDash(row.language) }}</template>
      </el-table-column>
      <el-table-column prop="runtime" label="运行时" width="80" />
      <el-table-column label="版本" width="90">
        <template #default="{ row }">{{ orDash(row.version) }}</template>
      </el-table-column>
      <el-table-column prop="ownerOrg" label="负责组织" min-width="120" />
      <el-table-column prop="ownerUser" label="负责人" width="90" />
    </el-table>

    <el-pagination
      v-model:current-page="filter.page"
      v-model:page-size="filter.size"
      class="pager"
      layout="total, sizes, prev, pager, next"
      :page-sizes="[10, 20, 50, 100]"
      :total="result?.total ?? 0"
    />
  </section>
</template>

<style scoped>
.panel { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 14px 16px; }
.bar { display: flex; gap: 10px; margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
