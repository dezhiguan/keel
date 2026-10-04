<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import { listAgents } from '@/api/agents'
import { listTraces, type ListTracesQuery, type TracePage } from '@/api/traces'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { ago, fmtCny, fmtMs, fmtN, nodeStatus } from '@/utils/format'

const router = useRouter()
const envStore = useEnvStore()
const agents = ref<string[]>([])
const result = ref<TracePage | null>(null)
const loading = ref(false)
const filter = reactive<{ agent: string; status?: ListTracesQuery['status']; page: number; size: NonNullable<ListTracesQuery['size']> }>({
  agent: '',
  status: undefined,
  page: 1,
  size: 10,
})

async function loadAgents() {
  try {
    const page = await listAgents({ env: envStore.env, page: 1, size: 100 })
    agents.value = (page.items ?? []).map((item) => item.name).filter((name): name is string => !!name)
  } catch {
    agents.value = []
  }
}

async function load() {
  loading.value = true
  try {
    result.value = await listTraces({
      env: envStore.env,
      agent: filter.agent || undefined,
      status: filter.status,
      page: filter.page,
      size: filter.size,
    })
  } catch (error) {
    ElMessage.error(`加载链路失败：${toKeelError(error).message}`)
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

watch(() => envStore.env, () => { loadAgents(); search() })
watch(() => [filter.page, filter.size], load, { immediate: true })
loadAgents()
</script>

<template>
  <div>
    <div class="vh">
      <h2>链路追踪</h2>
      <span class="sub">数据来自 Langfuse，叠加 Keel 的审计、审批、门禁信息</span>
    </div>
    <div class="toolbar">
      <select v-model="filter.agent" class="inp" @change="search">
        <option value="">全部智能体</option>
        <option v-for="a in agents" :key="a" :value="a">{{ a }}</option>
      </select>
      <div class="chipsel">
        <button :class="{ on: !filter.status }" @click="setStatus(undefined)">全部状态</button>
        <button :class="{ on: filter.status === 'ok' }" @click="setStatus('ok')">ok</button>
        <button :class="{ on: filter.status === 'fallback' }" @click="setStatus('fallback')">降级</button>
        <button :class="{ on: filter.status === 'failed' }" @click="setStatus('failed')">失败</button>
      </div>
    </div>

    <div v-loading="loading" class="card">
      <table class="t">
        <thead><tr><th>问题</th><th>智能体</th><th>开始</th><th>耗时</th><th>tokens</th><th>成本</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="t in result?.items ?? []" :key="t.traceId" class="click" @click="router.push(`/traces/${t.traceId}`)">
            <td class="nm" style="max-width: 420px">
              <b style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{{ t.question }}</b>
              <small class="mono">{{ t.traceId }}</small>
            </td>
            <td>
              <span v-if="t.multiAgent" class="pill nd p-acc">多智能体 · {{ t.agents?.length }}</span>
              <span v-else>{{ t.rootAgent }}</span>
            </td>
            <td class="mono">{{ ago(t.startedAt) }}</td>
            <td class="mono">
              {{ fmtMs(t.durationMs) }}
              <span v-if="t.humanWaitMs" class="mut"> + 人工 {{ Math.round(t.humanWaitMs / 1000) }}s</span>
            </td>
            <td class="mono">{{ fmtN(t.tokens) }}</td>
            <td class="mono">{{ fmtCny(t.costCny) }}</td>
            <td>
              <span v-if="t.blocked" class="pill p-bad">已拦截</span>
              <template v-else>
                <StatusPill v-bind="nodeStatus(t.status)" />
                <span v-if="t.cached" class="pill nd p-soft" style="margin-left: 4px">缓存</span>
              </template>
            </td>
          </tr>
          <tr v-if="!result?.items?.length && !loading"><td colspan="7" class="empty">没有符合条件的链路</td></tr>
        </tbody>
      </table>
      <Pager v-model:page="filter.page" v-model:size="filter.size" :total="result?.total ?? 0" />
    </div>
  </div>
</template>
