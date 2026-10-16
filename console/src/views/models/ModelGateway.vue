<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AgentName from '@/components/AgentName.vue'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import { createAlertRule, exportModelBills, getModelGateway, probeModel, rotateModelKey, updateModelBudget, type ModelGateway } from '@/api/models'
import { billStatusLabel, healthLabel, limitLabel, priceLabel } from './modelBill'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { useUserStore } from '@/stores/user'
import { fmtN, orDash, type StatusTone } from '@/utils/format'

type PageSize = 10 | 20 | 50 | 100
type GatewayKey = NonNullable<ModelGateway['keys']>[number]

const MODEL_STATUS: Record<string, { label: string; tone: StatusTone }> = {
  ok: { label: '正常', tone: 'ok' },
  warn: { label: '波动', tone: 'degraded' },
  bad: { label: '异常', tone: 'failed' },
}

const envStore = useEnvStore()
const userStore = useUserStore()
const data = ref<ModelGateway | null>(null)
const tab = ref<'model' | 'key' | 'route' | 'bill' | 'alert'>('model')
const ruleName = ref('')
const ruleMetric = ref('budget')
const ruleThreshold = ref(0.8)
const loading = ref(false)
const saving = ref(false)
const editing = ref<GatewayKey | null>(null)
const rotating = ref<GatewayKey | null>(null)
const rotated = ref<{ key: string; version: number } | null>(null)
const rotationBusy = ref(false)
const nextBudget = ref(30)
const modelPage = ref(1)
const modelSize = ref<PageSize>(10)
const keyPage = ref(1)
const keySize = ref<PageSize>(10)

const models = computed(() => data.value?.models ?? [])
const keys = computed(() => data.value?.keys ?? [])
const bills = computed(() => data.value?.bills ?? [])
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
  if (value == null) return '—'
  return `¥${value.toFixed(Math.abs(value) > 0 && Math.abs(value) < 0.01 ? 6 : 2)}`
}

async function probe(name?: string) {
  if (!name) return
  try {
    await probeModel(name)
    ElMessage.success(`${name} 已探测`)
    await load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
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

function openRotation(key: GatewayKey) {
  rotating.value = key
  rotated.value = null
}

function closeRotation() {
  if (rotationBusy.value) return
  rotating.value = null
  rotated.value = null
}

async function confirmRotation() {
  const alias = rotating.value?.alias
  if (!alias) return
  rotationBusy.value = true
  try {
    const result = await rotateModelKey(alias)
    rotated.value = { key: result.key, version: result.version }
    await load()
  } catch (error) {
    ElMessage.error(`轮换 Key 失败：${toKeelError(error).message}`)
  } finally {
    rotationBusy.value = false
  }
}

async function saveRule() {
  if (!ruleName.value.trim()) return
  try {
    await createAlertRule({ name: ruleName.value.trim(), metric: ruleMetric.value, threshold: ruleThreshold.value, window: '1d', groupBy: 'alias' })
    ruleName.value = ''
    await load()
  } catch (error) {
    ElMessage.error(`新建规则失败：${toKeelError(error).message}`)
  }
}

async function exportBills() {
  try {
    const csv = await exportModelBills({ env: envStore.env, range: '24h' })
    const blob = new Blob([csv], { type: 'text/csv' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'bills.csv'
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(`导出失败：${toKeelError(error).message}`)
  }
}

watch(() => envStore.env, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>模型与成本</h2>
      <span class="sub">后端是自研薄网关 keel-llm · 本页调 keel-server 取数和下发配置</span>
    </div>
    <div class="chipsel" style="margin-bottom: 14px">
      <button :class="{ on: tab === 'model' }" @click="tab = 'model'">模型与渠道 · {{ models.length }}</button>
      <button :class="{ on: tab === 'key' }" @click="tab = 'key'">Key 与配额 · {{ keys.length }}</button>
      <button :class="{ on: tab === 'route' }" @click="tab = 'route'">路由与降级</button>
      <button :class="{ on: tab === 'bill' }" @click="tab = 'bill'">账单 · 待计费 {{ data?.pendingCount ?? 0 }}</button>
      <button :class="{ on: tab === 'alert' }" @click="tab = 'alert'">告警规则</button>
    </div>

    <div v-if="tab === 'model'" class="card">
      <h3>模型</h3>
      <table class="t">
        <thead><tr><th>模型</th><th>提供方</th><th>用途</th><th>价格版本</th><th>健康</th><th>24h 调用</th><th>P95</th><th>超时率</th><th>成本</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="m in modelRows" :key="m.model">
            <td class="mono">{{ m.model }}<span v-if="m.priceConfigured === false" class="pill nd p-bad" style="margin-left: 6px">未配单价</span></td>
            <td>{{ orDash(m.provider) }}</td>
            <td>{{ orDash(m.role) }}</td>
            <td class="mono">{{ priceLabel(m.priceVersion, m.cacheCnyPerToken) }}<span v-if="m.drift" class="pill p-warn" style="margin-left: 6px">配置漂移</span></td>
            <td>{{ healthLabel(m.health) }} <button v-if="m.health !== 'LIVE'" v-write class="btn sm" type="button" @click="probe(m.model)">探测</button></td>
            <td class="mono">{{ fmtN(m.calls) }}</td>
            <td class="mono">{{ orDash(m.p95) }}</td>
            <td class="mono" :style="timeoutStyle(m.status)">{{ fmtTimeout(m.errorRate) }}</td>
            <td class="mono">{{ m.priceConfigured === false || m.costCny == null ? '—' : yuan(m.costCny) }}</td>
            <td><StatusPill v-bind="MODEL_STATUS[m.status ?? 'ok']" /></td>
          </tr>
          <tr v-if="!modelRows.length"><td colspan="10" class="empty">这个环境还没有模型</td></tr>
        </tbody>
      </table>
      <Pager v-model:page="modelPage" v-model:size="modelSize" :total="models.length" />
    </div>

    <div v-else-if="tab === 'key'" class="card">
      <h3>虚拟 Key 与日预算<small>keel register 自动创建，keel retire 自动吊销</small></h3>
      <table class="t">
        <thead><tr><th>智能体</th><th>Key 别名 · 版本</th><th>可用模型</th><th>RPM / TPM / 并发</th><th style="width: 28%">已结算 + 预留 / 日预算</th><th>状态</th><th /></tr></thead>
        <tbody>
          <tr v-for="k in keyRows" :key="k.alias">
            <td><AgentName :id="k.agent" /></td>
            <td class="mono">{{ k.alias }} · v{{ k.version ?? 1 }}</td>
            <td class="mono">{{ k.models?.length }}</td>
            <td class="mono">{{ limitLabel(k.rpm) }} / {{ limitLabel(k.tpm) }} / {{ limitLabel(k.concurrency) }}</td>
            <td>
              <div style="display: flex; justify-content: space-between; font-size: 12px">
                <span class="mono">{{ yuan(k.spentCny) }} + {{ yuan(k.reservedCny) }}</span><span class="mono mut">¥{{ k.dailyBudgetCny }}</span>
              </div>
              <div class="bar"><i :style="{ width: `${usage((k.spentCny ?? 0) + (k.reservedCny ?? 0), k.dailyBudgetCny)}%`, background: barColor(usage((k.spentCny ?? 0) + (k.reservedCny ?? 0), k.dailyBudgetCny)) }" /></div>
            </td>
            <td>
              <StatusPill v-if="k.status === 'BLOCKED'" tone="idle" label="已吊销" />
              <StatusPill v-else-if="usage((k.spentCny ?? 0) + (k.reservedCny ?? 0), k.dailyBudgetCny) > 60" tone="degraded" :label="`${Math.round(usage((k.spentCny ?? 0) + (k.reservedCny ?? 0), k.dailyBudgetCny))}%`" />
              <StatusPill v-else tone="ok" label="有效" />
            </td>
            <td style="white-space: nowrap">
              <template v-if="k.status !== 'BLOCKED'">
                <button v-write class="btn sm" @click="adjustBudget(k)">调整预算</button>
                <button v-if="userStore.user?.platformRole === 'ADMIN'" v-write class="btn sm" style="margin-left: 6px" @click="openRotation(k)">轮换</button>
              </template>
            </td>
          </tr>
          <tr v-if="!keyRows.length"><td colspan="7" class="empty">这个环境还没有虚拟 Key</td></tr>
        </tbody>
      </table>
      <Pager v-model:page="keyPage" v-model:size="keySize" :total="keys.length" />
    </div>

    <div v-else-if="tab === 'route'" class="card">
      <h3>路由与降级</h3>
      <div class="kv">
        <span>策略版本</span><b>{{ data?.routePolicy?.version ?? '未接入' }}</b>
        <span>目标</span><b>{{ data?.routePolicy?.objective ?? '未接入' }}</b>
        <span>降级</span><b>超时或 5xx 只切换一次。401、403 和预算不足不降级。</b>
        <span>熔断</span><b>同一渠道连续 2 次失败后跳过 60 秒</b>
      </div>
    </div>
    <div v-else-if="tab === 'alert'" class="card">
      <h3>告警规则<small>触发后进入运行总览</small></h3>
      <table class="t">
        <thead><tr><th>规则</th><th>指标</th><th>阈值</th><th>窗口</th><th>分组</th></tr></thead>
        <tbody>
          <tr v-for="rule in data?.alertRules ?? []" :key="rule.id">
            <td>{{ rule.name }}</td>
            <td class="mono">{{ rule.metric }}</td>
            <td class="mono">{{ rule.threshold }}</td>
            <td>{{ rule.window || '—' }}</td>
            <td>{{ rule.groupBy || '—' }}</td>
          </tr>
          <tr v-if="!(data?.alertRules ?? []).length"><td colspan="5" class="empty">还没有告警规则</td></tr>
        </tbody>
      </table>
      <div class="field" style="display: flex; gap: 8px; margin-top: 12px">
        <input v-model="ruleName" class="inp" placeholder="规则名称">
        <select v-model="ruleMetric" class="inp">
          <option value="budget">budget</option>
          <option value="usage_missing">usage_missing</option>
          <option value="channel_failure">channel_failure</option>
        </select>
        <input v-model.number="ruleThreshold" class="inp" type="number" min="0" step="0.1">
        <button v-write class="btn pri sm" type="button" @click="saveRule">新建规则</button>
      </div>
    </div>
    <div v-else class="card">
      <h3>账单<small>每次尝试一行 · 同一 requestId 只结算一次</small> <button class="btn sm" type="button" @click="exportBills">导出</button></h3>
      <table class="t">
        <thead><tr><th>时间</th><th>尝试</th><th>智能体</th><th>模型</th><th>价格版本</th><th>tokens</th><th>费用</th><th>状态</th><th>链路</th></tr></thead>
        <tbody>
          <tr v-for="(bill, index) in bills" :key="`${bill.attemptId || bill.requestId}-${index}`">
            <td class="mono">{{ bill.ts ? new Date(bill.ts).toLocaleString('zh-CN') : '—' }}</td>
            <td class="mono">{{ orDash(bill.attemptId || bill.requestId) }}</td>
            <td><AgentName :id="bill.agent" /></td>
            <td class="mono">{{ orDash(bill.model) }}</td>
            <td class="mono">{{ orDash(bill.priceVersion) }}<template v-if="bill.routeVersion"> · {{ bill.routeVersion }}</template></td>
            <td class="mono">{{ bill.inputTokens == null || bill.outputTokens == null ? '—' : fmtN(bill.inputTokens + bill.outputTokens) }}</td>
            <td class="mono">{{ bill.costCny == null ? '—' : yuan(bill.costCny) }}</td>
            <td><span class="pill" :class="bill.status === 'PENDING' ? 'p-warn' : bill.status === 'FAILED' ? 'p-bad' : 'p-ok'">{{ billStatusLabel(bill.status) }}</span></td>
            <td><RouterLink v-if="bill.traceId" :to="`/traces/${encodeURIComponent(bill.traceId)}`">查看</RouterLink><template v-else>—</template></td>
          </tr>
          <tr v-if="!bills.length"><td colspan="9" class="empty">当前窗口没有账单</td></tr>
        </tbody>
      </table>
    </div>

    <Teleport to="body">
      <div v-if="rotating" class="mask on" @click="closeRotation" />
      <div v-if="rotating" class="modal on" role="dialog" aria-label="轮换虚拟 Key">
        <div class="mh">轮换 Key · {{ rotating.alias }}</div>
        <div class="mb">
          <template v-if="rotated">
            <p>已签发 v{{ rotated.version }}。旧版本立即失效，智能体实例将重启。</p>
            <div class="field">
              <label>新 Key 明文（关闭后无法再次查看）</label>
              <input class="inp mono" style="width: 100%" readonly :value="rotated.key">
            </div>
          </template>
          <p v-else>将签发 v{{ (rotating.version ?? 1) + 1 }}，旧 Key 立即失效，并更新智能体 Secret、重启实例。</p>
        </div>
        <div class="mf">
          <button class="btn" type="button" :disabled="rotationBusy" @click="closeRotation">{{ rotated ? '关闭' : '取消' }}</button>
          <button v-if="!rotated" v-write class="btn pri" type="button" :disabled="rotationBusy" @click="confirmRotation">确认轮换</button>
        </div>
      </div>
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
          <button v-write class="btn pri" type="button" :disabled="saving" @click="saveBudget">保存</button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.budget-range { accent-color: var(--acc); width: 100%; }
</style>
