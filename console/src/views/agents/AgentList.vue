<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import { listAgents, type AgentPage, type AgentStatus, type ListAgentsQuery } from '@/api/agents'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { agentStatus, fmtN, orDash } from '@/utils/format'

const STATUSES: AgentStatus[] = ['DRAFT', 'REGISTERED', 'ONLINE', 'DEGRADED', 'OFFLINE', 'RETIRED']
const CATEGORIES: [NonNullable<ListAgentsQuery['category']>, string][] = [['all', '全部'], ['biz', '业务'], ['dev', '研发']]
const COLORS: Record<string, string> = {
  careermate: '#2ec4b6', askdb: '#5b9cf6', 'offshore-wind': '#34c38f', 'cs-bot': '#b48cf2', 'ops-copilot': '#ff7a45',
  'prd-agent': '#f1b44c', 'code-review': '#e36fae', 'test-gen': '#6fd3e3', 'ci-doctor': '#f46a6a', 'dev-copilot': '#ffb08f',
}

const router = useRouter()
const envStore = useEnvStore()
const result = ref<AgentPage | null>(null)
const loading = ref(false)
const filter = reactive<{
  category: NonNullable<ListAgentsQuery['category']>
  status: AgentStatus | ''
  q: string
  page: number
  size: NonNullable<ListAgentsQuery['size']>
}>({ category: 'all', status: '', q: '', page: 1, size: 10 })

const colorOf = (name = '') => COLORS[name] ?? '#8a97ab'

async function load() {
  loading.value = true
  try {
    result.value = await listAgents({
      env: envStore.env,
      category: filter.category,
      status: filter.status || undefined,
      q: filter.q.trim() || undefined,
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
  if (filter.page !== 1) filter.page = 1
  else load()
}

function setCategory(category: typeof filter.category) {
  filter.category = category
  search()
}

let typing: ReturnType<typeof setTimeout> | undefined
function onInput() {
  clearTimeout(typing)
  typing = setTimeout(search, 300)
}

watch(() => envStore.env, search)
watch(() => [filter.page, filter.size], load, { immediate: true })
</script>

<template>
  <div>
    <div class="vh">
      <h2>智能体</h2>
      <span class="sub">注册中心 · 共 {{ result?.total ?? 0 }} 个</span>
      <span class="sp" />
      <button class="btn pri" @click="router.push('/agents/new')">+ 新建智能体</button>
    </div>
    <div class="toolbar">
      <input v-model="filter.q" class="inp" placeholder="搜索名称、ID 或负责人" style="width: 220px" @input="onInput" />
      <div class="chipsel">
        <button v-for="[k, n] in CATEGORIES" :key="k" :class="{ on: filter.category === k }" @click="setCategory(k)">{{ n }}</button>
      </div>
      <select v-model="filter.status" class="inp" @change="search">
        <option value="">全部状态</option>
        <option v-for="s in STATUSES" :key="s" :value="s">{{ agentStatus(s).label }}</option>
      </select>
    </div>

    <div v-loading="loading" class="agrid">
      <div v-for="a in result?.items ?? []" :key="a.name" class="acard" @click="router.push(`/agents/${a.name}`)">
        <div class="hd">
          <div class="av" :style="{ background: `${colorOf(a.name)}22`, color: colorOf(a.name) }">{{ a.displayName?.slice(0, 1) }}</div>
          <div class="ttl"><b>{{ a.displayName }}</b><small>{{ a.name }}</small></div>
          <span class="sp" />
          <StatusPill v-bind="agentStatus(a.status)" />
        </div>
        <div class="meta">
          <span v-if="a.category" class="chip">{{ a.category === 'biz' ? '业务' : '研发' }}</span>
          <span class="chip">{{ a.runtime === 'dify' ? 'Dify' : orDash(a.language) }}</span>
          <span v-if="a.env" class="chip">{{ a.env }}</span>
          <span v-if="a.version" class="chip">{{ a.version }}</span>
          <span class="chip">{{ a.ownerOrg }} · {{ a.ownerUser }}</span>
        </div>
        <div class="stats">
          <div>24h 调用<b>{{ fmtN(a.calls24h) }}</b></div>
          <div>评测分<b :style="a.score !== null && a.score !== undefined && a.score < 0.85 ? { color: 'var(--bad)' } : undefined">{{ orDash(a.score) }}</b></div>
          <div>成本<b>{{ a.costCny === null || a.costCny === undefined ? '—' : `¥${a.costCny.toFixed(1)}` }}</b></div>
        </div>
      </div>
      <div class="acard new" @click="router.push('/agents/new')">
        <b>+</b>新建智能体
        <small class="mono" style="font-size: 11px">keel new 或向导</small>
      </div>
    </div>
    <Pager v-model:page="filter.page" v-model:size="filter.size" :total="result?.total ?? 0" />
  </div>
</template>
