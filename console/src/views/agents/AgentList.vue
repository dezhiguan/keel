<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import { getAgentUsage, listAgents, mergeUsage, type AgentPage, type AgentStatus } from '@/api/agents'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { agentStatus, fmtN } from '@/utils/format'
import { cardCost, scoreText } from './agentDrawer'
import AgentLineage from './AgentLineage.vue'
import { mergeEmployees, type EmployeeCard, type Layer } from './employees'

const STATUSES: AgentStatus[] = ['DRAFT', 'REGISTERED', 'ONLINE', 'DEGRADED', 'OFFLINE', 'RETIRED']
const CATEGORIES: [Layer | 'all', string][] = [['all', '全部'], ['biz', '业务'], ['dev', '研发'], ['meta', '元智能体（新）']]
const LAYER_LABEL: Record<Layer, string> = { meta: '元', dev: '研发', biz: '业务' }

const route = useRoute()
const router = useRouter()
const envStore = useEnvStore()
const result = ref<AgentPage | null>(null)
const loading = ref(false)
const view = ref<'card' | 'tree'>('card')
const filter = reactive<{
  category: Layer | 'all'
  status: AgentStatus | ''
  q: string
  page: number
  size: 10 | 20 | 50 | 100
}>({ category: 'all', status: '', q: '', page: 1, size: 10 })
const cards = computed(() => mergeEmployees(result.value?.items ?? []))
const shown = computed(() => {
  const q = filter.q.trim().toLowerCase()
  return cards.value.filter((card) => {
    if (filter.category !== 'all' && card.layer !== filter.category) return false
    if (filter.status && card.status !== filter.status) return false
    if (!q) return true
    return `${card.displayName} ${card.name} ${card.description}`.toLowerCase().includes(q)
  })
})
const pageRows = computed(() => shown.value.slice((filter.page - 1) * filter.size, filter.page * filter.size))
const registered = computed(() => result.value?.total ?? 0)

function open(name?: string) {
  if (!name) return
  router.push({ query: { ...route.query, drawer: name } })
}

let ticket = 0
async function load() {
  const current = ++ticket
  const env = envStore.env
  loading.value = true
  try {
    const page = await listAgents({
      env,
      category: 'all',
      page: 1,
      size: 100,
    })
    if (current !== ticket) return
    result.value = page
  } catch (error) {
    if (current !== ticket) return
    ElMessage.error(`加载智能体失败：${toKeelError(error).message}`)
    return
  } finally {
    if (current === ticket) loading.value = false
  }
  try {
    const usage = await getAgentUsage(env)
    if (current !== ticket || !result.value) return
    const byName = new Map((usage.items ?? []).map((item) => [item.name, item]))
    result.value = {
      ...result.value,
      items: (result.value.items ?? []).map((item) => mergeUsage(item, item.name ? byName.get(item.name) : undefined)),
    }
  } catch (error) {
    if (current !== ticket) return
    ElMessage.error(`加载用量失败：${toKeelError(error).message}`)
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

function yuan(value: number | null) {
  return value == null ? '—' : `¥${Number.isInteger(value) ? value : value.toFixed(1)}`
}

function mark(card: EmployeeCard) {
  return card.name.slice(0, 2).toUpperCase()
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
      <span class="sub">注册中心 · 共 {{ registered }} 个 · 未注册的按原型占位</span>
      <span class="sp" />
      <button v-write class="btn pri" @click="router.push('/agents/new')">+ 新建智能体</button>
    </div>
    <div class="toolbar">
      <input v-model="filter.q" class="inp" placeholder="搜索名称或 ID" style="width: 220px" @input="onInput" />
      <div class="chipsel">
        <button v-for="[k, n] in CATEGORIES" :key="k" :class="{ on: filter.category === k }" @click="setCategory(k)">{{ n }}</button>
      </div>
      <select v-model="filter.status" class="inp" @change="search">
        <option value="">全部状态</option>
        <option v-for="s in STATUSES" :key="s" :value="s">{{ agentStatus(s).label }}</option>
      </select>
      <span class="sp" />
      <div class="chipsel">
        <button type="button" :class="{ on: view === 'card' }" @click="view = 'card'">卡片</button>
        <button type="button" :class="{ on: view === 'tree' }" @click="view = 'tree'">谱系（新）</button>
      </div>
    </div>

    <AgentLineage v-if="view === 'tree'" :cards="shown" @open="open" />
    <template v-else>
      <div v-loading="loading" class="agrid">
        <div v-for="card in pageRows" :key="card.name" class="acard" role="button" tabindex="0" @click="open(card.name)" @keydown.enter="open(card.name)">
          <div class="hd">
            <div class="av" :style="{ background: card.color, color: '#fff' }">{{ mark(card) }}</div>
            <div class="ttl"><b>{{ card.displayName }}</b><small>{{ card.name }} · {{ card.template }}</small></div>
            <span class="sp" />
            <span class="ly" :class="card.layer">{{ LAYER_LABEL[card.layer] }}</span>
            <StatusPill v-if="card.status" v-bind="agentStatus(card.status as AgentStatus)" />
          </div>
          <p>{{ card.description }}</p>
          <div class="src">来源 <span v-if="card.placeholder" class="tag-new">占位</span><span v-else class="tag-new">新</span>：
            <RouterLink v-if="card.jobId" :to="`/jobs/${card.jobId}`" @click.stop>{{ card.source }}</RouterLink>
            <template v-else>{{ card.source }}</template>
            · 负责人 {{ card.owner }}
          </div>
          <div class="st3">
            <div>{{ card.placeholder ? '本月调用' : '24h 调用' }}<b>{{ fmtN(card.calls) }}</b></div>
            <div>质量<b>{{ scoreText(card.score) }}</b></div>
            <div>日预算<b>{{ yuan(card.budget) }}</b></div>
            <div>本月花费<b>{{ cardCost(card.cost) }}</b></div>
          </div>
        </div>
        <div class="acard new" @click="router.push('/agents/new')">
          <b>+</b>新建智能体
          <small class="mono" style="font-size: 11px">keel new 或向导</small>
        </div>
      </div>
      <Pager v-model:page="filter.page" v-model:size="filter.size" :total="shown.length" />
    </template>
  </div>
</template>
