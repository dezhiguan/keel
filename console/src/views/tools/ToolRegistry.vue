<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ToolStatusPill from './ToolStatusPill.vue'
import { listTools, type ListToolsQuery, type ToolPage } from '@/api/tools'
import { toKeelError } from '@/api/http'
import { RISK, fmtN } from '@/utils/format'

const ACCESS = { READ: '读', WRITE: '写', EXEC: '执行' } as const
const SCOPE = { PRIVATE: '私有', SHARED: '共享' } as const

const router = useRouter()
const result = ref<ToolPage | null>(null)
const loading = ref(false)
const filter = reactive<{ scope: NonNullable<ListToolsQuery['scope']>; page: number; size: NonNullable<ListToolsQuery['size']> }>({
  scope: 'all',
  page: 1,
  size: 10,
})

async function load() {
  loading.value = true
  try {
    result.value = await listTools({ scope: filter.scope, page: filter.page, size: filter.size })
  } catch (error) {
    ElMessage.error(`加载工具失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

function setScope(scope: typeof filter.scope) {
  filter.scope = scope
  filter.page = 1
  load()
}

function registerTool() {
  // TODO(P2-10): registration dialog for POST /tools (ToolRegisterRequest).
  ElMessage.info('注册 MCP 工具尚未接入')
}

watch(() => [filter.page, filter.size], load, { immediate: true })
</script>

<template>
  <div>
    <div class="vh">
      <h2>工具</h2>
      <span class="sub">统一注册，声明读写属性和风险等级。高风险工具的每次调用都会被挂起，送到审批中心</span>
      <span class="sp" />
      <button class="btn pri" @click="registerTool">+ 注册 MCP 工具</button>
    </div>
    <div class="toolbar">
      <div class="chipsel">
        <button :class="{ on: filter.scope === 'all' }" @click="setScope('all')">全部</button>
        <button :class="{ on: filter.scope === 'SHARED' }" @click="setScope('SHARED')">共享</button>
        <button :class="{ on: filter.scope === 'PRIVATE' }" @click="setScope('PRIVATE')">私有</button>
      </div>
    </div>
    <div v-loading="loading" class="card">
      <table class="t">
        <thead><tr><th>工具</th><th>范围</th><th>所有者</th><th>读写</th><th>风险</th><th>版本</th><th>依赖方</th><th>24h</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="t in result?.items ?? []" :key="t.name" class="click" @click="router.push(`/tools/${t.name}`)">
            <td class="nm"><b class="mono">{{ t.name }}</b><small>{{ t.description }}</small></td>
            <td>{{ SCOPE[t.scope!] }}</td>
            <td>{{ t.ownerAgent ?? t.ownerOrg }}</td>
            <td>{{ ACCESS[t.access!] }}</td>
            <td><span class="pill nd" :class="RISK[t.risk!].cls">{{ RISK[t.risk!].label }}</span></td>
            <td class="mono">{{ t.version }}</td>
            <td><span v-if="t.dependentCount" class="mono">{{ t.dependentCount }}</span><span v-else class="mut">无</span></td>
            <td class="mono">{{ fmtN(t.calls24h) }}</td>
            <td>
              <ToolStatusPill :status="t.status" />
              <div v-if="t.status === 'DEPRECATED'" class="mut" style="font-size: 11px">→ {{ t.replacedBy }} · {{ t.deprecateDeadline }}</div>
            </td>
          </tr>
        </tbody>
      </table>
      <el-pagination
        v-model:current-page="filter.page"
        v-model:page-size="filter.size"
        class="pager"
        layout="total, sizes, prev, pager, next"
        :page-sizes="[10, 20, 50, 100]"
        :total="result?.total ?? 0"
      />
    </div>
  </div>
</template>
