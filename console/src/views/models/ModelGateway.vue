<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AgentName from '@/components/AgentName.vue'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import {
  createAlertRule,
  exportModelBills,
  getModelGateway,
  previewRoute,
  probeModel,
  publishRoutePolicy,
  rollbackRoutePolicy,
  rotateModelKey,
  updateModelBudget,
  type ModelGateway,
} from '@/api/models'
import {
  billClock,
  billStatusLabel,
  capabilityLabel,
  groupLabel,
  healthPill,
  limitLabel,
  metricLabel,
  objectiveLabel,
  routeReason,
  thresholdLabel,
  unitPrice,
} from './modelBill'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { useUserStore } from '@/stores/user'
import { fmtN, orDash } from '@/utils/format'

type PageSize = 10 | 20 | 50 | 100
type GatewayKey = NonNullable<ModelGateway['keys']>[number]
type GatewayModel = NonNullable<ModelGateway['models']>[number]
type Objective = 'balanced' | 'cost' | 'latency'

const OBJECTIVES: { id: Objective; label: string }[] = [
  { id: 'balanced', label: '均衡' },
  { id: 'cost', label: '成本优先' },
  { id: 'latency', label: '延迟优先' },
]

const envStore = useEnvStore()
const userStore = useUserStore()
const data = ref<ModelGateway | null>(null)
const tab = ref<'model' | 'key' | 'route'>('model')
const loading = ref(false)
const saving = ref(false)
const selectedAlias = ref('')
const nextBudget = ref(30)
const rotating = ref<GatewayKey | null>(null)
const rotated = ref<{ key: string; version: number } | null>(null)
const rotationBusy = ref(false)
const modelPage = ref(1)
const modelSize = ref<PageSize>(10)
const keyPage = ref(1)
const keySize = ref<PageSize>(10)
const pendingObjective = ref<Objective | null>(null)
const routeBusy = ref(false)
const previewAlias = ref('')
const previewModel = ref('')
const previewText = ref('选择 Key 和模型后试算，显示策略版本和实际会尝试的模型。')
const previewTone = ref<'info' | 'warn'>('info')
const ruleOpen = ref(false)
const ruleName = ref('')
const ruleMetric = ref('budget')
const ruleThreshold = ref(0.8)
const ruleWindow = ref('15m')
const ruleGroup = ref('alias')

const models = computed(() => data.value?.models ?? [])
const keys = computed(() => data.value?.keys ?? [])
const bills = computed(() => data.value?.bills ?? [])
const modelRows = computed(() => models.value.slice((modelPage.value - 1) * modelSize.value, modelPage.value * modelSize.value))
const keyRows = computed(() => keys.value.slice((keyPage.value - 1) * keySize.value, keyPage.value * keySize.value))
const chains = computed(() => keys.value.filter((key) => (key.models?.length ?? 0) > 0))
const selectedKey = computed(() => keys.value.find((key) => key.alias === selectedAlias.value) ?? null)
const previewChoices = computed(() => keys.value.find((key) => key.alias === previewAlias.value)?.models ?? [])
const admin = computed(() => userStore.user?.platformRole === 'ADMIN')
const objective = computed(() => data.value?.routePolicy?.objective)
const policyVersion = computed(() => data.value?.routePolicy?.version)

const usage = (spent = 0, budget = 1) => Math.min(100, ((spent) / budget) * 100)
const barColor = (percent: number) => (percent > 80 ? 'var(--bad)' : percent > 60 ? 'var(--warn)' : 'var(--teal)')

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

function priceOf(model: GatewayModel) {
  return unitPrice(model.inputCnyPerToken, model.outputCnyPerToken, model.cacheCnyPerToken, model.priceVersion)
}

function channelOf(model: GatewayModel) {
  const provider = (model.provider || 'unknown').toLowerCase()
  return `${model.model}@${provider}`
}

function selectKey(key: GatewayKey) {
  selectedAlias.value = key.alias ?? ''
  const current = key.dailyBudgetCny ?? 30
  const stepped = Math.round(current / 5) * 5
  nextBudget.value = Math.min(200, Math.max(10, stepped))
}

async function load() {
  loading.value = true
  try {
    data.value = await getModelGateway({ env: envStore.env, range: '24h' })
    clamp(modelPage, modelSize.value, models.value.length)
    clamp(keyPage, keySize.value, keys.value.length)
    const current = keys.value.find((key) => key.alias === selectedAlias.value) ?? keys.value[0]
    if (current) selectKey(current)
    if (!previewAlias.value && keys.value[0]?.alias) {
      previewAlias.value = keys.value[0].alias
      previewModel.value = keys.value[0].models?.[0] ?? ''
    }
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

async function saveBudget() {
  const key = selectedKey.value
  if (!key?.alias || key.status === 'BLOCKED') return
  saving.value = true
  try {
    await updateModelBudget(key.alias, nextBudget.value)
    ElMessage.success(`已更新 ${key.agent} 日预算为 ¥${nextBudget.value}`)
    await load()
  } catch (error) {
    ElMessage.error(`调整配额失败：${toKeelError(error).message}`)
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

function askObjective(next: Objective) {
  if (!admin.value || next === objective.value || routeBusy.value) return
  pendingObjective.value = next
}

async function confirmObjective() {
  const next = pendingObjective.value
  if (!next) return
  routeBusy.value = true
  try {
    await publishRoutePolicy(next)
    ElMessage.success(`路由策略已发布为${objectiveLabel(next)}`)
    pendingObjective.value = null
    await load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    routeBusy.value = false
  }
}

async function rollback() {
  if (!admin.value) return
  if ((policyVersion.value ?? 1) <= 1) {
    ElMessage.warning('只有一版，不能回滚')
    return
  }
  routeBusy.value = true
  try {
    const policy = await rollbackRoutePolicy()
    ElMessage.success(`已回到 v${policy.version} · ${objectiveLabel(policy.objective)}`)
    await load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    routeBusy.value = false
  }
}

async function simulate() {
  if (!previewAlias.value || !previewModel.value) {
    previewTone.value = 'warn'
    previewText.value = '先选择 Key 和模型。'
    return
  }
  routeBusy.value = true
  try {
    const result = await previewRoute(previewAlias.value, previewModel.value)
    const chain = result.models?.length ? result.models.join(' → ') : '没有可用模型'
    previewTone.value = result.models?.length ? 'info' : 'warn'
    previewText.value = `v${result.version} · ${objectiveLabel(result.objective)} · ${chain}`
  } catch (error) {
    previewTone.value = 'warn'
    previewText.value = toKeelError(error).message
  } finally {
    routeBusy.value = false
  }
}

async function saveRule() {
  if (!ruleName.value.trim()) return
  try {
    await createAlertRule({
      name: ruleName.value.trim(),
      metric: ruleMetric.value,
      threshold: ruleThreshold.value,
      window: ruleWindow.value,
      groupBy: ruleGroup.value,
    })
    ruleName.value = ''
    ruleOpen.value = false
    ElMessage.success('已新建告警规则')
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

watch(previewAlias, (alias) => {
  const modelsForKey = keys.value.find((key) => key.alias === alias)?.models ?? []
  if (!modelsForKey.includes(previewModel.value)) previewModel.value = modelsForKey[0] ?? ''
})

watch(() => envStore.env, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>模型与成本</h2>
    </div>
    <div class="dtabs">
      <button type="button" :class="{ on: tab === 'model' }" @click="tab = 'model'">模型与渠道</button>
      <button type="button" :class="{ on: tab === 'key' }" @click="tab = 'key'">Key 与配额</button>
      <button type="button" :class="{ on: tab === 'route' }" @click="tab = 'route'">路由</button>
    </div>

    <template v-if="tab === 'model'">
      <div class="card">
        <table class="t">
          <thead>
            <tr>
              <th>模型</th>
              <th>渠道</th>
              <th>能力</th>
              <th>单价 输入 / 输出 / 缓存</th>
              <th>24h 调用</th>
              <th>P95</th>
              <th>超时率</th>
              <th>成本</th>
              <th>健康</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="model in modelRows" :key="model.model">
              <td class="mono">{{ model.model }}</td>
              <td>{{ orDash(model.provider) }}</td>
              <td class="cap">{{ capabilityLabel(model.role, model.embedding) }}</td>
              <td class="mono price">
                {{ priceOf(model).prices }}
                <div class="mut ver">{{ priceOf(model).version }}</div>
              </td>
              <td class="mono">{{ fmtN(model.calls) }}</td>
              <td class="mono">{{ orDash(model.p95) }}</td>
              <td class="mono" :style="timeoutStyle(model.status)">{{ fmtTimeout(model.errorRate) }}</td>
              <td class="mono">{{ model.priceConfigured === false || model.costCny == null ? '—' : yuan(model.costCny) }}</td>
              <td class="health">
                <StatusPill v-bind="healthPill(model)" />
                <button v-if="model.health !== 'LIVE'" v-write class="btn sm" type="button" @click="probe(model.model)">探测</button>
              </td>
            </tr>
            <tr v-if="!modelRows.length"><td colspan="9" class="empty">这个环境还没有模型</td></tr>
          </tbody>
        </table>
        <Pager v-model:page="modelPage" v-model:size="modelSize" :total="models.length" />
      </div>
      <div class="card">
        <h3>
          账单
          <small>近 24 小时 · 待计费 {{ data?.pendingCount ?? 0 }}</small>
          <span class="sp" />
          <button class="btn sm" type="button" @click="exportBills">导出</button>
        </h3>
        <table class="t">
          <thead>
            <tr>
              <th>时间</th>
              <th>requestId</th>
              <th>智能体</th>
              <th>模型</th>
              <th>路由</th>
              <th>tokens</th>
              <th>费用</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(bill, index) in bills" :key="`${bill.attemptId || bill.requestId}-${index}`">
              <td class="mono">{{ billClock(bill.ts) }}</td>
              <td class="mono">{{ orDash(bill.requestId) }}</td>
              <td><AgentName :id="bill.agent" /></td>
              <td class="mono">{{ orDash(bill.model) }}</td>
              <td><span class="pill nd p-soft">{{ routeReason(bill.routeVersion) }}</span></td>
              <td class="mono">{{ bill.inputTokens == null || bill.outputTokens == null ? '—' : fmtN(bill.inputTokens + bill.outputTokens) }}</td>
              <td class="mono">{{ bill.costCny == null ? '—' : yuan(bill.costCny) }}</td>
              <td><span class="pill" :class="bill.status === 'PENDING' ? 'p-warn' : bill.status === 'FAILED' ? 'p-bad' : 'p-ok'">{{ billStatusLabel(bill.status) }}</span></td>
            </tr>
            <tr v-if="!bills.length"><td colspan="8" class="empty">当前窗口没有账单</td></tr>
          </tbody>
        </table>
      </div>
    </template>

    <div v-else-if="tab === 'key'" class="row2">
      <div class="card">
        <table class="t">
          <thead>
            <tr>
              <th>智能体</th>
              <th>Key</th>
              <th>可用模型</th>
              <th>已结算 + 预留 / 日预算</th>
              <th>RPM / TPM</th>
              <th>并发</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="key in keyRows"
              :key="key.alias"
              class="click"
              :class="{ sel: key.alias === selectedAlias }"
              @click="selectKey(key)"
            >
              <td><AgentName :id="key.agent" /></td>
              <td class="mono">{{ key.alias }} · v{{ key.version ?? 1 }}</td>
              <td class="mono">{{ key.models?.length }}</td>
              <td>
                <div class="budget-line">
                  <span class="mono">{{ yuan(key.spentCny) }} + {{ yuan(key.reservedCny) }}</span>
                  <span class="mono mut">¥{{ key.dailyBudgetCny }}</span>
                </div>
                <div class="bar"><i :style="{ width: `${usage((key.spentCny ?? 0) + (key.reservedCny ?? 0), key.dailyBudgetCny)}%`, background: barColor(usage((key.spentCny ?? 0) + (key.reservedCny ?? 0), key.dailyBudgetCny)) }" /></div>
              </td>
              <td class="mono limit">{{ limitLabel(key.rpm) }} / {{ limitLabel(key.tpm) }}</td>
              <td class="mono">{{ limitLabel(key.concurrency) }}</td>
              <td>
                <StatusPill v-if="key.status === 'BLOCKED'" tone="idle" label="已吊销" />
                <StatusPill v-else-if="usage((key.spentCny ?? 0) + (key.reservedCny ?? 0), key.dailyBudgetCny) > 60" tone="degraded" :label="`${Math.round(usage((key.spentCny ?? 0) + (key.reservedCny ?? 0), key.dailyBudgetCny))}%`" />
                <StatusPill v-else tone="ok" label="有效" />
              </td>
            </tr>
            <tr v-if="!keyRows.length"><td colspan="7" class="empty">这个环境还没有虚拟 Key</td></tr>
          </tbody>
        </table>
        <Pager v-model:page="keyPage" v-model:size="keySize" :total="keys.length" />
      </div>
      <div class="card">
        <h3>配额 · <span class="mono">{{ selectedKey?.alias || '—' }}</span></h3>
        <template v-if="selectedKey">
          <div class="kv">
            <span>可用模型</span><b>{{ selectedKey.models?.join('、') || '—' }}</b>
            <span>RPM / TPM</span><b class="mono">{{ limitLabel(selectedKey.rpm) }} / {{ limitLabel(selectedKey.tpm) }}</b>
            <span>并发</span><b class="mono">{{ limitLabel(selectedKey.concurrency) }}</b>
            <span>今日已用</span><b class="mono">{{ yuan(selectedKey.spentCny) }} + {{ yuan(selectedKey.reservedCny) }}</b>
          </div>
          <div class="field">
            <label>调整为：<b class="budget-now">¥{{ nextBudget }}</b> <span class="mut">当前 ¥{{ selectedKey.dailyBudgetCny }}</span></label>
            <input v-model.number="nextBudget" class="budget-range" type="range" min="10" max="200" step="5" :disabled="selectedKey.status === 'BLOCKED'">
          </div>
          <div class="ops">
            <button v-if="selectedKey.status !== 'BLOCKED'" v-write class="btn pri" type="button" :disabled="saving" @click="saveBudget">保存</button>
            <button v-if="admin && selectedKey.status !== 'BLOCKED'" v-write class="btn" type="button" @click="openRotation(selectedKey)">轮换</button>
          </div>
        </template>
        <p v-else class="empty">还没有可选的 Key</p>
      </div>
    </div>

    <template v-else>
      <div class="row2">
        <div class="card">
          <h3>
            路由策略
            <small class="mono">v{{ policyVersion ?? '—' }} · {{ objectiveLabel(objective) }}</small>
            <span class="sp" />
            <button v-if="admin" v-write class="btn sm" type="button" :disabled="routeBusy" @click="rollback">回滚</button>
          </h3>
          <div class="chipsel">
            <button
              v-for="item in OBJECTIVES"
              :key="item.id"
              type="button"
              :class="{ on: objective === item.id }"
              @click="askObjective(item.id)"
            >{{ item.label }}</button>
          </div>
          <div class="kv route-rules">
            <span>先用指定模型</span><b>请求里写了模型就先用它</b>
            <span>降级条件</span><b>超时或 5xx 只切换一次</b>
            <span>不降级</span><b>401、403 和预算不足。向量模型不降级</b>
            <span>熔断</span><b>同一渠道连续 2 次失败后跳过 60 秒，冷却后放行一次探测</b>
          </div>
          <h3 class="subhead">降级链</h3>
          <table class="t">
            <tbody>
              <tr v-for="key in chains" :key="key.alias">
                <td class="mono">{{ key.alias }}</td>
                <td>{{ key.models?.join(' → ') }}</td>
              </tr>
              <tr v-if="!chains.length"><td class="empty">这个环境还没有降级链</td></tr>
            </tbody>
          </table>
          <h3 class="subhead">渠道熔断</h3>
          <table class="t">
            <tbody>
              <tr v-for="model in models" :key="model.model">
                <td class="mono">{{ channelOf(model) }}</td>
                <td class="mono" :style="timeoutStyle(model.status)">{{ fmtTimeout(model.errorRate) }}</td>
                <td><span class="pill p-mute">未接入</span></td>
              </tr>
              <tr v-if="!models.length"><td class="empty">还没有渠道</td></tr>
            </tbody>
          </table>
        </div>
        <div class="card">
          <h3>路由试算</h3>
          <div class="field">
            <label>虚拟 Key</label>
            <select v-model="previewAlias" class="inp">
              <option v-for="key in keys" :key="key.alias" :value="key.alias">{{ key.alias }}</option>
            </select>
          </div>
          <div class="field">
            <label>指定模型</label>
            <select v-model="previewModel" class="inp">
              <option v-for="name in previewChoices" :key="name" :value="name">{{ name }}</option>
            </select>
          </div>
          <button v-write class="btn pri sim" type="button" :disabled="routeBusy" @click="simulate">试算</button>
          <div class="banner" :class="previewTone">{{ previewText }}</div>
        </div>
      </div>
      <div class="card">
        <h3>
          告警规则
          <small>触发后进入运行总览</small>
          <span class="sp" />
          <button v-write class="btn sm pri" type="button" @click="ruleOpen = true">+ 新建规则</button>
        </h3>
        <table class="t">
          <thead><tr><th>规则</th><th>指标</th><th>阈值</th><th>窗口</th><th>分组</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="rule in data?.alertRules ?? []" :key="rule.id">
              <td><b>{{ rule.name }}</b></td>
              <td>{{ metricLabel(rule.metric) }}</td>
              <td class="mono">{{ thresholdLabel(rule.metric, rule.threshold) }}</td>
              <td>{{ rule.window || '—' }}</td>
              <td>{{ groupLabel(rule.groupBy) }}</td>
              <td><span class="pill p-ok">启用</span></td>
            </tr>
            <tr v-if="!(data?.alertRules ?? []).length"><td colspan="6" class="empty">还没有告警规则</td></tr>
          </tbody>
        </table>
      </div>
    </template>

    <Teleport to="body">
      <div v-if="pendingObjective" class="mask on" @click="pendingObjective = null" />
      <div v-if="pendingObjective" class="modal on" role="dialog" aria-label="发布路由策略">
        <div class="mh">发布路由策略</div>
        <div class="mb">
          <p>将从「{{ objectiveLabel(objective) }}」发布为「{{ objectiveLabel(pendingObjective) }}」，版本加一。已发出的请求不受影响。</p>
        </div>
        <div class="mf">
          <button class="btn" type="button" :disabled="routeBusy" @click="pendingObjective = null">取消</button>
          <button v-write class="btn pri" type="button" :disabled="routeBusy" @click="confirmObjective">发布</button>
        </div>
      </div>

      <div v-if="ruleOpen" class="mask on" @click="ruleOpen = false" />
      <div v-if="ruleOpen" class="modal on" role="dialog" aria-label="新建告警规则">
        <div class="mh">新建告警规则</div>
        <div class="mb">
          <div class="field">
            <label>规则名称</label>
            <input v-model="ruleName" class="inp" placeholder="例如 预算接近上限">
          </div>
          <div class="field">
            <label>指标</label>
            <select v-model="ruleMetric" class="inp">
              <option value="budget">已结算 + 预留 / 日预算</option>
              <option value="usage_missing">待计费条数</option>
              <option value="channel_failure">错误率</option>
            </select>
          </div>
          <div class="grid2">
            <div class="field">
              <label>阈值</label>
              <input v-model.number="ruleThreshold" class="inp" type="number" min="0" step="0.1">
            </div>
            <div class="field">
              <label>窗口</label>
              <input v-model="ruleWindow" class="inp" placeholder="15m">
            </div>
          </div>
          <div class="field">
            <label>分组</label>
            <select v-model="ruleGroup" class="inp">
              <option value="alias">智能体</option>
              <option value="model">模型</option>
              <option value="channel">渠道</option>
              <option value="env">环境</option>
            </select>
          </div>
        </div>
        <div class="mf">
          <button class="btn" type="button" @click="ruleOpen = false">取消</button>
          <button v-write class="btn pri" type="button" @click="saveRule">新建</button>
        </div>
      </div>

      <div v-if="rotating" class="mask on" @click="closeRotation" />
      <div v-if="rotating" class="modal on" role="dialog" aria-label="轮换虚拟 Key">
        <div class="mh">轮换 Key · {{ rotating.alias }}</div>
        <div class="mb">
          <template v-if="rotated">
            <p>已签发 v{{ rotated.version }}。旧版本立即失效，智能体实例将重启。</p>
            <div class="field">
              <label>新 Key 明文（关闭后无法再次查看）</label>
              <input class="inp mono" readonly :value="rotated.key">
            </div>
          </template>
          <p v-else>将签发 v{{ (rotating.version ?? 1) + 1 }}，旧 Key 立即失效，并更新智能体 Secret、重启实例。</p>
        </div>
        <div class="mf">
          <button class="btn" type="button" :disabled="rotationBusy" @click="closeRotation">{{ rotated ? '关闭' : '取消' }}</button>
          <button v-if="!rotated" v-write class="btn pri" type="button" :disabled="rotationBusy" @click="confirmRotation">确认轮换</button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.budget-range { accent-color: var(--acc); width: 100%; }
.budget-now { color: var(--white); }
.budget-line { display: flex; justify-content: space-between; font-size: 12px; }
.cap, .price { font-size: 12px; }
.ver { font-size: 10.5px; }
.health, .ops { white-space: nowrap; }
.health .btn { margin-left: 6px; }
.ops { display: flex; gap: 8px; margin-top: 14px; }
.limit { font-size: 12px; }
.subhead { margin-top: 16px; }
.sim { width: 100%; margin-top: 8px; }
.route-rules { margin-top: 14px; }
.banner { margin-top: 12px; }
.t tr.sel { background: #131d2e; }
</style>
