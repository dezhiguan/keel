<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import {
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
import { billClock, billStatusLabel, limitLabel, objectiveLabel } from './modelBill'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { useUserStore } from '@/stores/user'
import { fmtN } from '@/utils/format'

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
const previewText = ref('选择 Key 和模型后试算，结果来自薄网关。')
const previewTone = ref<'info' | 'warn'>('info')

const models = computed(() => data.value?.models ?? [])
const keys = computed(() => data.value?.keys ?? [])
const bills = computed(() => data.value?.bills ?? [])
const failedBills = computed(() => bills.value.filter((bill) => bill.status === 'FAILED'))
const admin = computed(() => userStore.user?.platformRole === 'ADMIN')
const objective = computed(() => data.value?.routePolicy?.objective)
const policyVersion = computed(() => data.value?.routePolicy?.version)
const selectedKey = computed(() => keys.value.find((key) => key.alias === selectedAlias.value) ?? null)
const previewChoices = computed(() => keys.value.find((key) => key.alias === previewAlias.value)?.models ?? [])
const keyRows = computed(() => keys.value.slice((keyPage.value - 1) * keySize.value, keyPage.value * keySize.value))
const routeHeads = computed(() => {
  const names = models.value.map((model) => model.model).filter((name): name is string => !!name)
  const primary = names.find((name) => name.includes('qwen')) ?? names[0] ?? '主模型'
  const secondary = names.find((name) => name !== primary) ?? '降级模型'
  return { primary, secondary }
})

function weightOf(key: GatewayKey, model: string) {
  const allowed = key.models ?? []
  if (!allowed.includes(model)) return '—'
  const known = [routeHeads.value.primary, routeHeads.value.secondary].filter((name) => allowed.includes(name))
  return known.length === 1 ? '100%' : '—'
}

function fallbackOf(key: GatewayKey) {
  const rest = (key.models ?? []).slice(1)
  return rest.length ? rest.join(' → ') : '无'
}

const channels = computed(() => models.value.map((model) => {
  const name = model.model || ''
  const users = keys.value.filter((key) => key.models?.includes(name))
  const envs = [...new Set(users.map((key) => key.env).filter(Boolean))]
  const caps = users.map((key) => key.rpm).filter((rpm): rpm is number => rpm != null)
  const total = keys.value.length
  const used = users.length
  const percent = total ? Math.round((used / total) * 100) : 0
  return {
    model,
    host: channelHost(model.provider),
    envs: envs.length ? envs.join('、') : '—',
    rpmLabel: caps.length ? String(Math.max(...caps)) : '不限制',
    used,
    total,
    percent,
    tone: percent > 85 ? 'p-warn' : 'p-ok',
    color: percent > 85 ? 'var(--warn)' : 'var(--soft)',
    usage: usageText(model),
    health: healthView(model),
  }
}))
const channelRows = computed(() => channels.value.slice((modelPage.value - 1) * modelSize.value, modelPage.value * modelSize.value))

const sameEnvModels = computed(() => {
  const env = selectedKey.value?.env
  if (!env) return models.value
  const names = new Set(keys.value.filter((key) => key.env === env).flatMap((key) => key.models ?? []))
  const listed = models.value.filter((model) => names.has(model.model || ''))
  return listed.length ? listed : models.value
})

function channelHost(provider?: string | null) {
  const name = (provider || '').toLowerCase()
  if (name.includes('dash') || name.includes('qwen') || name.includes('百炼')) return 'dashscope.aliyuncs.com'
  if (name.includes('deepseek')) return 'api.deepseek.com'
  return '—'
}

function usageText(model: GatewayModel) {
  if (model.errorRate == null || Number.isNaN(model.errorRate)) return model.calls == null ? '—' : `${fmtN(model.calls)} 次`
  const pct = model.errorRate * 100
  return pct === 0 ? '0%' : `${pct.toFixed(1)}%`
}

function healthView(model: GatewayModel) {
  if (model.priceConfigured === false) return { tone: 'p-bad', label: '未配单价' }
  if (model.drift) return { tone: 'p-bad', label: '配置漂移' }
  if (model.status === 'bad') return { tone: 'p-bad', label: '异常' }
  if (model.status === 'warn') return { tone: 'p-warn', label: '波动' }
  if (model.health === 'LIVE') return { tone: 'p-ok', label: '有真实调用' }
  if (model.health === 'PROBED') return { tone: 'p-soft', label: '已探测' }
  if (model.health === 'CONFIGURED') return { tone: 'p-mute', label: '已配置' }
  return { tone: 'p-mute', label: '—' }
}

function yuan(value?: number | null) {
  if (value == null) return '—'
  return `¥${value.toFixed(Math.abs(value) > 0 && Math.abs(value) < 0.01 ? 6 : 2)}`
}

function selectKey(key: GatewayKey) {
  selectedAlias.value = key.alias ?? ''
  const current = key.dailyBudgetCny ?? 30
  const stepped = Math.round(current / 5) * 5
  nextBudget.value = Math.min(200, Math.max(10, stepped || 10))
}

async function load() {
  loading.value = true
  try {
    data.value = await getModelGateway({ env: envStore.env, range: '24h' })
    clamp(modelPage, modelSize.value, channels.value.length)
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
    ElMessage.success(`已更新 ${key.alias} 日预算为 ¥${nextBudget.value}`)
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
  <div v-loading="loading" class="models">
    <div class="vh">
      <h2>模型与成本</h2>
    </div>
    <div class="dtabs">
      <button type="button" :class="{ on: tab === 'model' }" @click="tab = 'model'">模型与渠道</button>
      <button type="button" :class="{ on: tab === 'key' }" @click="tab = 'key'">Key 与配额</button>
      <button type="button" :class="{ on: tab === 'route' }" @click="tab = 'route'">路由</button>
    </div>

    <div v-show="tab === 'model'">
      <div class="card">
        <h3>
          渠道
          <span class="sp" />
          <button class="btn sm" type="button" @click="exportBills">导出账单</button>
        </h3>
        <table class="t">
          <thead>
            <tr>
              <th>渠道</th>
              <th>模型</th>
              <th>绑定环境</th>
              <th title="RPM：每分钟请求数">厂商上限 RPM<br><small>每分钟请求数</small></th>
              <th>已分配</th>
              <th>当前用量</th>
              <th>状态</th>
              <th>熔断（按渠道）</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in channelRows" :key="row.model.model">
              <td class="nm"><b>{{ row.model.provider || '—' }}</b><small>{{ row.host }}</small></td>
              <td>{{ row.model.model }}</td>
              <td>{{ row.envs }}</td>
              <td class="mono">{{ row.rpmLabel }}</td>
              <td>
                <div class="bar"><i :style="{ width: `${row.percent}%`, background: row.color }" /></div>
                <span class="mono alloc">{{ row.used }} / {{ row.total }} · {{ row.percent }}%</span>
              </td>
              <td class="mono">{{ row.usage }}</td>
              <td><span class="pill" :class="row.health.tone">{{ row.health.label }}</span></td>
              <td><span class="pill p-mute">未接入</span></td>
              <td>
                <button v-if="row.model.health !== 'LIVE'" v-write class="btn sm" type="button" @click="probe(row.model.model)">探测</button>
              </td>
            </tr>
            <tr v-if="!channelRows.length"><td colspan="9" class="empty">这个环境还没有模型</td></tr>
          </tbody>
        </table>
        <Pager v-model:page="modelPage" v-model:size="modelSize" :total="channels.length" />
      </div>
    </div>

    <div v-show="tab === 'key'">
      <div class="row2">
        <div class="card">
          <h3>Key 份额 <small>点一行查看这把 Key 在网关上的配额</small></h3>
          <div class="mut legend">RPM = 每分钟请求数 · TPM = 每分钟 Token 数（输入 + 输出）· 并发 = 同时进行中的请求数</div>
          <table class="t">
            <thead>
              <tr>
                <th>Key</th>
                <th title="RPM：每分钟请求数">允许的渠道 · RPM（每分钟请求数）</th>
                <th>路由覆盖</th>
                <th title="TPM：每分钟 Token 数">TPM（每分钟 Token 数）</th>
                <th title="同时进行中的请求数">并发</th>
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
                <td class="mono">{{ key.alias }}</td>
                <td>
                  <span v-for="name in key.models ?? []" :key="name" class="pill nd p-mute share">{{ name }} · {{ limitLabel(key.rpm) }}</span>
                  <span v-if="!(key.models ?? []).length" class="mut">—</span>
                </td>
                <td>
                  <span v-if="key.models?.length" class="pill p-ok">已覆盖</span>
                  <span v-else class="pill p-bad">缺模型</span>
                </td>
                <td class="mono">{{ key.tpm == null ? '不限制' : key.tpm.toLocaleString('zh-CN') }}</td>
                <td class="mono">{{ limitLabel(key.concurrency) }}</td>
              </tr>
              <tr v-if="!keyRows.length"><td colspan="5" class="empty">这个环境还没有虚拟 Key</td></tr>
            </tbody>
          </table>
          <Pager v-model:page="keyPage" v-model:size="keySize" :total="keys.length" />
        </div>
        <div class="card">
          <h3>分配份额 · <span class="mono">{{ selectedKey?.alias || '—' }}</span></h3>
          <template v-if="selectedKey">
            <div class="field">
              <label>允许的渠道（只列同环境）</label>
              <div v-for="model in sameEnvModels" :key="model.model" class="ch-row">
                <label>
                  <input type="checkbox" disabled :checked="selectedKey.models?.includes(model.model || '')">
                  {{ model.provider || '渠道' }}
                  <span class="mut">{{ model.model }}</span>
                </label>
                <input class="inp rpm" type="text" disabled :value="limitLabel(selectedKey.rpm)" title="RPM：每分钟请求数">
              </div>
            </div>
            <div class="share-grid">
              <div class="field">
                <label>TPM（每分钟 Token 数，输入 + 输出）</label>
                <input class="inp" type="text" disabled :value="selectedKey.tpm == null ? '不限制' : selectedKey.tpm.toLocaleString('zh-CN')">
              </div>
              <div class="field">
                <label>并发</label>
                <input class="inp" type="text" disabled :value="limitLabel(selectedKey.concurrency)">
              </div>
            </div>
            <div class="field">
              <label>日预算：<b class="budget-now">¥{{ nextBudget }}</b> <span class="mut">当前 ¥{{ selectedKey.dailyBudgetCny }}</span></label>
              <input v-model.number="nextBudget" class="budget-range" type="range" min="10" max="200" step="5" :disabled="selectedKey.status === 'BLOCKED'">
            </div>
            <div v-if="selectedKey.models?.length" class="pbanner ok">
              已允许的模型：{{ selectedKey.models.join('、') }}。
              <div class="mut">今日 {{ yuan(selectedKey.spentCny) }} + {{ yuan(selectedKey.reservedCny) }} / ¥{{ selectedKey.dailyBudgetCny }}</div>
            </div>
            <div v-else class="pbanner bad">这把 Key 没有允许的模型，请求发不出去。</div>
            <div class="ops">
              <button v-if="selectedKey.status !== 'BLOCKED'" v-write class="btn pri" type="button" :disabled="saving" @click="saveBudget">保存</button>
              <button v-if="admin && selectedKey.status !== 'BLOCKED'" v-write class="btn" type="button" @click="openRotation(selectedKey)">轮换</button>
            </div>
          </template>
          <p v-else class="empty">还没有可选的 Key</p>
        </div>
      </div>
      <div class="card">
        <h3>
          最近失败的请求
          <small>来自近 24 小时账单</small>
          <span class="sp" />
          <button class="btn sm" type="button" @click="exportBills">导出</button>
        </h3>
        <table class="t">
          <thead><tr><th>时间</th><th>Key</th><th>结果</th><th>原因</th></tr></thead>
          <tbody>
            <tr v-for="(bill, index) in failedBills" :key="`${bill.attemptId || bill.requestId}-${index}`">
              <td class="mono">{{ billClock(bill.ts) }}</td>
              <td class="mono">{{ bill.alias || '—' }}</td>
              <td><span class="pill p-bad">{{ billStatusLabel(bill.status) }}</span></td>
              <td class="mut">{{ bill.model || '—' }} · {{ yuan(bill.costCny) }}</td>
            </tr>
            <tr v-if="!failedBills.length"><td colspan="4" class="mut">当前窗口没有失败请求</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-show="tab === 'route'">
      <div class="card">
        <h3>
          路由表
          <span class="pill nd p-soft mono">已发布 v{{ policyVersion ?? '—' }}</span>
          <small>{{ objectiveLabel(objective) }}</small>
          <span class="sp" />
          <button v-if="admin" v-write class="btn sm" type="button" :disabled="routeBusy" @click="rollback">回滚</button>
          <button v-if="admin" v-write class="btn pri sm" type="button" :disabled="routeBusy || !pendingObjective" @click="confirmObjective">发布</button>
        </h3>
        <table class="t route-table">
          <thead>
            <tr>
              <th>逻辑模型</th>
              <th>条件</th>
              <th>{{ routeHeads.primary }} 权重</th>
              <th>{{ routeHeads.secondary }}</th>
              <th>降级</th>
              <th>灰度</th>
              <th>Key 覆盖</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="key in keys" :key="key.alias">
              <td class="mono">{{ key.alias }}</td>
              <td>{{ key.env || '—' }}</td>
              <td class="mono">{{ weightOf(key, routeHeads.primary) }}</td>
              <td class="mono">{{ weightOf(key, routeHeads.secondary) }}</td>
              <td>{{ fallbackOf(key) }}</td>
              <td><span class="mut">—</span></td>
              <td>
                <span v-if="key.models?.length" class="pill p-ok">已覆盖</span>
                <span v-else class="pill p-bad">缺模型</span>
              </td>
            </tr>
            <tr v-if="!keys.length"><td colspan="7" class="empty">这个环境还没有降级链</td></tr>
          </tbody>
        </table>
        <div class="chipsel route-obj">
          <button
            v-for="item in OBJECTIVES"
            :key="item.id"
            type="button"
            :class="{ on: (pendingObjective ?? objective) === item.id }"
            @click="askObjective(item.id)"
          >{{ item.label }}</button>
        </div>
        <div class="pbanner ok">请求里写了模型就先用它。超时或 5xx 只切换一次；401、403 和预算不足不降级。熔断状态没有列表，渠道页显示未接入。</div>
      </div>
      <div class="card">
        <h3>
          路由试算
          <small>按当前已发布策略，向薄网关查询这把 Key 会尝试的模型</small>
          <span class="sp" />
          <button v-write class="btn pri sm" type="button" :disabled="routeBusy" @click="simulate">试算</button>
        </h3>
        <div class="share-grid">
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
        </div>
        <div class="banner" :class="previewTone">{{ previewText }}</div>
      </div>
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
.row2 { grid-template-columns: 1.25fr 1fr; }
.pbanner { display: block; margin: 10px 0; }
.t tr.sel { background: #131d2e; }
.bar { min-width: 90px; }
th small { font-weight: 400; }
.legend { font-size: 11.5px; margin: -6px 0 8px; }
.alloc { font-size: 11.5px; }
.share { margin: 1px 2px; }
.ch-row { display: flex; align-items: center; gap: 10px; padding: 5px 0; border-bottom: 1px solid #172235; }
.ch-row label { display: flex; gap: 8px; align-items: center; flex: 1; margin: 0; color: var(--text); font-size: 12.8px; }
.ch-row .mut { font-size: 11.5px; }
.ch-row .rpm { width: 90px; flex: none; }
.share-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.budget-range { accent-color: var(--acc); width: 100%; }
.budget-now { color: var(--white); }
.ops { display: flex; gap: 8px; margin-top: 8px; }
.route-table { margin-top: 0; }
.route-obj { margin-top: 12px; }
.banner { margin-top: 12px; }
</style>
