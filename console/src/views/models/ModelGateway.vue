<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import { getModelGateway, updateModelBudget, type ModelGateway } from '@/api/models'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { fmtN, orDash, type StatusTone } from '@/utils/format'

type PageSize = 10 | 20 | 50 | 100
type GatewayKey = NonNullable<ModelGateway['keys']>[number]

const MODEL_STATUS: Record<string, { label: string; tone: StatusTone }> = {
  ok: { label: '正常', tone: 'ok' },
  warn: { label: '波动', tone: 'degraded' },
  bad: { label: '异常', tone: 'failed' },
}

const envStore = useEnvStore()
const data = ref<ModelGateway | null>(null)
const tab = ref<'model' | 'key'>('model')
const loading = ref(false)
const saving = ref(false)
const editing = ref<GatewayKey | null>(null)
const nextBudget = ref(30)
const modelPage = ref(1)
const modelSize = ref<PageSize>(10)
const keyPage = ref(1)
const keySize = ref<PageSize>(10)

const models = computed(() => data.value?.models ?? [])
const keys = computed(() => data.value?.keys ?? [])
const modelRows = computed(() => models.value.slice((modelPage.value - 1) * modelSize.value, modelPage.value * modelSize.value))
const keyRows = computed(() => keys.value.slice((keyPage.value - 1) * keySize.value, keyPage.value * keySize.value))

const usage = (spent = 0, budget = 1) => Math.min(100, (spent / budget) * 100)
const barColor = (p: number) => (p > 80 ? 'var(--bad)' : p > 60 ? 'var(--warn)' : 'var(--teal)')

function fmtTimeout(rate?: number | null) {
  if (rate == null || Number.isNaN(rate)) return '—'
  const pct = rate * 100
  return pct === 0 ? '0%' : `${pct.toFixed(1)}%`
}

function timeoutStyle(status?: string) {
  if (status === 'warn') return { color: 'var(--warn)' }
  if (status === 'bad') return { color: 'var(--bad)' }
  return undefined
}

function yuan(value?: number | null) {
  return `¥${(value ?? 0).toFixed(2)}`
}

async function load() {
  loading.value = true
  try {
    data.value = await getModelGateway({ env: envStore.env, range: '24h' })
    clamp(modelPage, modelSize.value, models.value.length)
    clamp(keyPage, keySize.value, keys.value.length)
  } catch (error) {
    ElMessage.error(`加载模型网关失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

function clamp(page: { value: number }, size: number, total: number) {
  const pages = Math.max(1, Math.ceil(total / size))
  if (page.value > pages) page.value = pages
}

function adjustBudget(key: GatewayKey) {
  editing.value = key
  const current = key.dailyBudgetCny ?? 30
  const stepped = Math.round(current / 5) * 5
  nextBudget.value = Math.min(200, Math.max(10, stepped))
}

async function saveBudget() {
  const key = editing.value
  if (!key?.alias) return
  saving.value = true
  try {
    await updateModelBudget(key.alias, nextBudget.value)
    ElMessage.success(`已更新 ${key.agent} 日预算为 ¥${nextBudget.value}，薄网关已同步`)
    editing.value = null
    await load()
  } catch (error) {
    ElMessage.error(`调整预算失败：${toKeelError(error).message}`)
  } finally {
    saving.value = false
  }
}

watch(() => envStore.env, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>模型网关</h2>
      <span class="sub">后端是自研薄网关 keel-llm · 本页调 keel-server 取数和下发配置</span>
    </div>
    <div class="chipsel" style="margin-bottom: 14px">
      <button :class="{ on: tab === 'model' }" @click="tab = 'model'">模型 · {{ models.length }}</button>
      <button :class="{ on: tab === 'key' }" @click="tab = 'key'">虚拟 Key 与日预算 · {{ keys.length }}</button>
    </div>

    <div v-if="tab === 'model'" class="card">
      <h3>模型</h3>
      <table class="t">
        <thead><tr><th>模型</th><th>提供方</th><th>用途</th><th>24h 调用</th><th>P95</th><th>超时率</th><th>成本</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="m in modelRows" :key="m.model">
            <td class="mono">{{ m.model }}<span v-if="m.priceConfigured === false" class="pill nd p-bad" style="margin-left: 6px">未配单价</span></td>
            <td>{{ orDash(m.provider) }}</td>
            <td>{{ orDash(m.role) }}</td>
            <td class="mono">{{ fmtN(m.calls) }}</td>
            <td class="mono">{{ orDash(m.p95) }}</td>
            <td class="mono" :style="timeoutStyle(m.status)">{{ fmtTimeout(m.errorRate) }}</td>
            <td class="mono">{{ m.priceConfigured === false || m.costCny == null ? '—' : yuan(m.costCny) }}</td>
            <td><StatusPill v-bind="MODEL_STATUS[m.status ?? 'ok']" /></td>
          </tr>
          <tr v-if="!modelRows.length"><td colspan="8" class="empty">这个环境还没有模型</td></tr>
        </tbody>
      </table>
      <Pager v-model:page="modelPage" v-model:size="modelSize" :total="models.length" />
    </div>

    <div v-else class="card">
      <h3>虚拟 Key 与日预算<small>keel register 自动创建，keel retire 自动吊销</small></h3>
      <table class="t">
        <thead><tr><th>智能体</th><th>Key 别名</th><th>可用模型</th><th style="width: 34%">今日 / 日预算</th><th>状态</th><th /></tr></thead>
        <tbody>
          <tr v-for="k in keyRows" :key="k.alias">
            <td>{{ k.agent }}</td>
            <td class="mono">{{ k.alias }}</td>
            <td class="mono">{{ k.models?.length }}</td>
            <td>
              <div style="display: flex; justify-content: space-between; font-size: 12px">
                <span class="mono">{{ yuan(k.spentCny) }}</span><span class="mono mut">¥{{ k.dailyBudgetCny }}</span>
              </div>
              <div class="bar"><i :style="{ width: `${usage(k.spentCny, k.dailyBudgetCny)}%`, background: barColor(usage(k.spentCny, k.dailyBudgetCny)) }" /></div>
            </td>
            <td>
              <StatusPill v-if="k.status === 'BLOCKED'" tone="idle" label="已吊销" />
              <StatusPill v-else-if="usage(k.spentCny, k.dailyBudgetCny) > 60" tone="degraded" :label="`${Math.round(usage(k.spentCny, k.dailyBudgetCny))}%`" />
              <StatusPill v-else tone="ok" label="有效" />
            </td>
            <td><button v-if="k.status !== 'BLOCKED'" class="btn sm" @click="adjustBudget(k)">调整预算</button></td>
          </tr>
          <tr v-if="!keyRows.length"><td colspan="6" class="empty">这个环境还没有虚拟 Key</td></tr>
        </tbody>
      </table>
      <Pager v-model:page="keyPage" v-model:size="keySize" :total="keys.length" />
    </div>

    <Teleport to="body">
      <div v-if="editing" class="mask on" @click="editing = null" />
      <div v-if="editing" class="modal on" role="dialog" aria-label="调整日预算">
        <div class="mh">调整日预算 · {{ editing.agent }}</div>
        <div class="mb">
          <div class="field">
            <label>日预算：<b style="color: var(--white)">¥{{ nextBudget }}</b>（今日已用 {{ yuan(editing.spentCny) }}）</label>
            <input v-model.number="nextBudget" class="budget-range" type="range" min="10" max="200" step="5">
          </div>
          <p class="mut" style="font-size: 12px">保存后同步写到薄网关虚拟 Key <span class="mono">{{ editing.alias }}</span>，并记入审计。</p>
        </div>
        <div class="mf">
          <button class="btn" type="button" :disabled="saving" @click="editing = null">取消</button>
          <button class="btn pri" type="button" :disabled="saving" @click="saveBudget">保存</button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.budget-range { accent-color: var(--acc); width: 100%; }
</style>
