<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import {
  addModelChannel,
  getModelGateway,
  previewRoute,
  probeModel,
  probeModelChannel,
  rollbackRoutePolicy,
  rotateModelKey,
  simulateChannelFailures,
  switchProdChannel,
  updateModelBudget,
  type ModelGateway,
} from '@/api/models'
import { breakerLabel } from './channelBreaker'
import { billClock, billStatusLabel, limitLabel, objectiveLabel } from './modelBill'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { useUserStore } from '@/stores/user'
import { fmtN } from '@/utils/format'

type PageSize = 10 | 20 | 50 | 100
type GatewayKey = NonNullable<ModelGateway['keys']>[number]
type GatewayModel = NonNullable<ModelGateway['models']>[number]
type LiveChannel = {
  id: string
  name?: string
  model?: string
  env?: string
  host?: string
  rpm?: number | null
  builtin?: boolean
  prod?: boolean
  breaker?: string
  openUntil?: number
  bound?: number
}

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
const routeBusy = ref(false)
const previewAlias = ref('')
const previewModel = ref('')
const previewText = ref('选择 Key 和模型后试算，结果来自薄网关。')
const previewTone = ref<'info' | 'warn'>('info')
const nowMs = ref(Date.now())
const channelBusy = ref(false)
const addOpen = ref(false)
const switchOpen = ref(false)
const addName = ref('百炼·主账号B·生产空间')
const addModel = ref('')
const addEnv = ref<'prod' | 'dev' | 'test' | 'staging'>('prod')
const addRpm = ref(1200)
const switchTo = ref('')
let clock = 0

const models = computed(() => data.value?.models ?? [])
const keys = computed(() => data.value?.keys ?? [])
const bills = computed(() => data.value?.bills ?? [])
const failedBills = computed(() => bills.value.filter((bill) => bill.status === 'FAILED'))
const simulated = ref<{ time: string; alias: string; tone: string; result: string; reason: string }[]>([])
const admin = computed(() => userStore.user?.platformRole === 'ADMIN')
const objective = computed(() => data.value?.routePolicy?.objective)
const policyVersion = computed(() => data.value?.routePolicy?.version)
const quotaOpen = ref(false)
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
const liveChannels = computed(() => ((data.value as { channels?: LiveChannel[] } | null)?.channels ?? []))
const usingLive = computed(() => liveChannels.value.length > 0)
const visibleLive = computed(() => {
  const env = envStore.env
  if (!env || env === 'all') return liveChannels.value
  return liveChannels.value.filter((channel) => (channel.env || '').split('、').includes(env))
})
const livePage = computed(() => visibleLive.value.slice((modelPage.value - 1) * modelSize.value, modelPage.value * modelSize.value))
const prodQwen = computed(() => liveChannels.value.filter((channel) => channel.prod && (channel.model || '').toLowerCase().includes('qwen')))
const switchHint = computed(() => (prodQwen.value.length < 2
  ? '只有一个 prod 千问渠道。先「新增渠道」加一个。密钥沿用该模型在 Secret keel-llm-vendors 里的厂商密钥，页面不提交密钥。'
  : '把允许该千问模型的 prod Key 改走目标渠道。网关热生效，不重新部署。'))
const modelChoices = computed(() => models.value.map((model) => model.model).filter((name): name is string => !!name))

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

function usageText(model?: GatewayModel) {
  if (!model || model.errorRate == null || Number.isNaN(model.errorRate)) return !model || model.calls == null ? '—' : `${fmtN(model.calls)} 次`
  const pct = model.errorRate * 100
  return pct === 0 ? '0%' : `${pct.toFixed(1)}%`
}

function healthView(model?: GatewayModel) {
  if (!model) return { tone: 'p-mute', label: '—' }
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

function openQuota(key: GatewayKey) {
  selectKey(key)
  quotaOpen.value = true
}

function closeQuota() {
  if (saving.value) return
  quotaOpen.value = false
}

async function load() {
  loading.value = true
  try {
    data.value = await getModelGateway({ env: envStore.env, range: '24h' })
    clamp(modelPage, modelSize.value, usingLive.value ? visibleLive.value.length : channels.value.length)
    clamp(keyPage, keySize.value, keys.value.length)
    const current = keys.value.find((key) => key.alias === selectedAlias.value)
    if (current) selectKey(current)
    else quotaOpen.value = false
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

function channelFits(channel: LiveChannel, env?: string | null) {
  if (!env) return true
  const listed = (channel.env || '').split('、').filter(Boolean)
  return !listed.length || listed.includes(env)
}

function simulateOnce() {
  const pool = keys.value.filter((key) => (key.models?.length ?? 0) > 0)
  if (!pool.length) {
    ElMessage.warning('这个环境还没有可用的 Key')
    return
  }
  const key = pool[Math.floor(Math.random() * pool.length)]
  const model = key.models?.[0] || ''
  const same = liveChannels.value.filter((channel) => channel.model === model && channelFits(channel, key.env))
  const from = same[0] ?? liveChannels.value.find((channel) => channel.model === model)
  const fromName = from?.name || model || '渠道'
  const healthy = (channel: LiveChannel) => breakerLabel(channel.breaker, channel.openUntil, nowMs.value).label === '正常'
  const alt = same.find((channel) => channel.id !== from?.id && healthy(channel))
  const time = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  const vendor = Math.random() < 0.6
  if (!vendor) {
    simulated.value.unshift({
      time,
      alias: key.alias || '—',
      tone: 'p-warn',
      result: '网关拒绝 429',
      reason: `超出 Key 在「${fromName}」的份额 RPM ${limitLabel(key.rpm)}，未打到厂商，不换渠道`,
    })
  } else if (alt) {
    simulated.value.unshift({
      time,
      alias: key.alias || '—',
      tone: 'p-ok',
      result: '厂商 429 → 已换渠道',
      reason: `「${fromName}」返回 429（Retry-After 20s，期间跳过）→ 改打本 Key 允许的同模型「${alt.name}」成功，计 1 次切换`,
    })
  } else {
    simulated.value.unshift({
      time,
      alias: key.alias || '—',
      tone: 'p-bad',
      result: '厂商限流 429',
      reason: `「${fromName}」达到厂商上限；同模型同环境没有可用渠道，返回 429 并记渠道告警。可「新增渠道」加一个生产账号`,
    })
  }
  simulated.value = simulated.value.slice(0, 6)
}

watch(previewAlias, (alias) => {
  const modelsForKey = keys.value.find((key) => key.alias === alias)?.models ?? []
  if (!modelsForKey.includes(previewModel.value)) previewModel.value = modelsForKey[0] ?? ''
})


function modelOf(name?: string) {
  return models.value.find((model) => model.model === name)
}

function liveRpm(channel: LiveChannel) {
  if (channel.rpm != null) return String(channel.rpm)
  const caps = keys.value.filter((key) => key.models?.includes(channel.model || '')).map((key) => key.rpm).filter((rpm): rpm is number => rpm != null)
  return caps.length ? String(Math.max(...caps)) : '不限制'
}

function liveAlloc(channel: LiveChannel) {
  const peers = keys.value.filter((key) => key.models?.includes(channel.model || '')).length
  const used = channel.bound ?? 0
  const total = channel.rpm ?? Math.max(peers, used, 1)
  const percent = Math.min(100, Math.round((used / total) * 100))
  return {
    percent,
    color: percent > 85 ? 'var(--warn)' : 'var(--soft)',
    text: channel.rpm != null ? `${used} / ${channel.rpm} · ${percent}%` : `${used} 把 Key`,
  }
}

function openAdd() {
  addName.value = '百炼·主账号B·生产空间'
  addModel.value = modelChoices.value.find((name) => name.includes('qwen')) || modelChoices.value[0] || ''
  addEnv.value = 'prod'
  addRpm.value = 1200
  addOpen.value = true
}

function openSwitch() {
  switchTo.value = prodQwen.value[0]?.id || ''
  switchOpen.value = true
}

function closeChannelDialogs() {
  if (channelBusy.value) return
  addOpen.value = false
  switchOpen.value = false
}

async function trip(channel: LiveChannel) {
  if (!channel.id || channelBusy.value) return
  channelBusy.value = true
  try {
    await simulateChannelFailures(channel.id)
    ElMessage.warning(`「${channel.name}」连续 2 次 5xx，熔断 60 秒。同地址的其他渠道不受影响`)
    await load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    channelBusy.value = false
  }
}

async function releaseProbe(channel: LiveChannel) {
  if (!channel.id || channelBusy.value) return
  channelBusy.value = true
  try {
    await probeModelChannel(channel.id)
    ElMessage.success(`「${channel.name}」探测成功，恢复正常`)
    await load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    channelBusy.value = false
  }
}

async function confirmAdd() {
  if (!addName.value.trim() || !addModel.value || addRpm.value < 1) {
    ElMessage.warning('填写渠道名称、模型和大于 0 的 RPM')
    return
  }
  channelBusy.value = true
  try {
    await addModelChannel({ name: addName.value.trim(), model: addModel.value, env: addEnv.value, rpm: addRpm.value })
    addOpen.value = false
    ElMessage.success('渠道已新增。prod Key 要切换过来才会用到')
    await load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    channelBusy.value = false
  }
}

async function confirmSwitch() {
  if (prodQwen.value.length < 2 || !switchTo.value) return
  channelBusy.value = true
  try {
    const moved = await switchProdChannel(switchTo.value)
    switchOpen.value = false
    ElMessage.success(moved.moved ? `已把 ${moved.moved} 把 prod Key 切到「${moved.name}」` : '没有需要切换的 Key')
    await load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    channelBusy.value = false
  }
}

onMounted(() => {
  clock = window.setInterval(() => { nowMs.value = Date.now() }, 1000)
})
onUnmounted(() => window.clearInterval(clock))

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
          <button v-if="admin" v-write class="btn sm" type="button" @click="openSwitch">切换 prod 渠道</button>
          <button v-if="admin" v-write class="btn pri sm" type="button" @click="openAdd">+ 新增渠道</button>
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
            <template v-if="usingLive">
              <tr v-for="row in livePage" :key="row.id">
                <td class="nm"><b>{{ row.name || '—' }}</b><small>{{ row.host || '—' }}</small></td>
                <td>{{ row.model }}</td>
                <td>{{ row.env || '—' }}</td>
                <td class="mono">{{ liveRpm(row) }}</td>
                <td>
                  <div class="bar"><i :style="{ width: `${liveAlloc(row).percent}%`, background: liveAlloc(row).color }" /></div>
                  <span class="mono alloc">{{ liveAlloc(row).text }}</span>
                </td>
                <td class="mono">{{ usageText(modelOf(row.model)) }}</td>
                <td><span class="pill" :class="healthView(modelOf(row.model)).tone">{{ healthView(modelOf(row.model)).label }}</span></td>
                <td>
                  <span class="pill" :class="breakerLabel(row.breaker, row.openUntil, nowMs).tone">{{ breakerLabel(row.breaker, row.openUntil, nowMs).label }}</span>
                  <span v-if="breakerLabel(row.breaker, row.openUntil, nowMs).remain" class="mono mut">{{ breakerLabel(row.breaker, row.openUntil, nowMs).remain }}s</span>
                </td>
                <td>
                  <button v-if="admin && breakerLabel(row.breaker, row.openUntil, nowMs).action === 'trip'" v-write class="btn sm" type="button" :disabled="channelBusy" @click="trip(row)">模拟 2 次 5xx</button>
                  <button v-else-if="admin && breakerLabel(row.breaker, row.openUntil, nowMs).action === 'probe'" v-write class="btn sm" type="button" :disabled="channelBusy" @click="releaseProbe(row)">放行探测</button>
                </td>
              </tr>
              <tr v-if="!livePage.length"><td colspan="9" class="empty">这个环境还没有渠道</td></tr>
            </template>
            <template v-else>
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
            </template>
          </tbody>
        </table>
        <div class="mut note">点「模拟 2 次 5xx」只熔断该渠道 60 秒，之后半开放行一次探测。同地址的其他渠道不受影响。</div>
        <Pager v-model:page="modelPage" v-model:size="modelSize" :total="usingLive ? visibleLive.length : channels.length" />
      </div>
    </div>

    <div v-show="tab === 'key'">
      <div class="card">
        <h3>Key 份额 <small>点「配额」查看这把 Key 在网关上的配额</small></h3>
          <div class="mut legend">RPM = 每分钟请求数 · TPM = 每分钟 Token 数（输入 + 输出）· 并发 = 同时进行中的请求数</div>
          <table class="t">
            <thead>
              <tr>
                <th>Key</th>
                <th title="RPM：每分钟请求数">允许的渠道 · RPM（每分钟请求数）</th>
                <th>路由覆盖</th>
                <th title="TPM：每分钟 Token 数">TPM（每分钟 Token 数）</th>
                <th title="同时进行中的请求数">并发</th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr v-for="key in keyRows" :key="key.alias">
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
                <td>
                  <button class="btn sm quota" type="button" @click="openQuota(key)">配额</button>
                </td>
              </tr>
              <tr v-if="!keyRows.length"><td colspan="6" class="empty">这个环境还没有虚拟 Key</td></tr>
            </tbody>
          </table>
          <Pager v-model:page="keyPage" v-model:size="keySize" :total="keys.length" />
      </div>
      <div class="card">
        <h3>
          最近被拒绝的请求
          <small>区分网关份额和厂商限流</small>
          <span class="sp" />
          <button v-write class="btn sm" type="button" @click="simulateOnce">模拟一次请求</button>
        </h3>
        <table class="t">
          <thead><tr><th>时间</th><th>Key</th><th>结果</th><th>原因</th></tr></thead>
          <tbody>
            <tr v-for="(row, index) in simulated" :key="`sim-${index}-${row.time}`">
              <td class="mono">{{ row.time }}</td>
              <td class="mono">{{ row.alias }}</td>
              <td><span class="pill" :class="row.tone">{{ row.result }}</span></td>
              <td class="mut">{{ row.reason }}</td>
            </tr>
            <tr v-for="(bill, index) in failedBills" :key="`${bill.attemptId || bill.requestId}-${index}`">
              <td class="mono">{{ billClock(bill.ts) }}</td>
              <td class="mono">{{ bill.alias || '—' }}</td>
              <td><span class="pill p-bad">{{ billStatusLabel(bill.status) }}</span></td>
              <td class="mut">{{ bill.model || '—' }} · {{ yuan(bill.costCny) }}</td>
            </tr>
            <tr v-if="!simulated.length && !failedBills.length"><td colspan="4" class="mut">点「模拟一次请求」</td></tr>
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
        <div class="pbanner ok">请求里写了模型就先用它。超时或 5xx 只切换一次；401、403 和预算不足不降级。渠道连续 2 次 5xx 后熔断 60 秒。</div>
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
      <div v-if="quotaOpen && selectedKey" class="mask on" @click="closeQuota" />
      <div v-if="quotaOpen && selectedKey" class="modal quota on" role="dialog" aria-label="分配份额">
        <div class="mh">分配份额 · <span class="mono">{{ selectedKey.alias }}</span></div>
        <div class="mb">
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
        </div>
        <div class="mf">
          <button class="btn" type="button" :disabled="saving" @click="closeQuota">关闭</button>
          <button v-if="admin && selectedKey.status !== 'BLOCKED'" v-write class="btn" type="button" @click="openRotation(selectedKey)">轮换</button>
          <button v-if="selectedKey.status !== 'BLOCKED'" v-write class="btn pri" type="button" :disabled="saving" @click="saveBudget">保存</button>
        </div>
      </div>
      <div v-if="rotating" class="mask on over" @click="closeRotation" />
      <div v-if="rotating" class="modal on over" role="dialog" aria-label="轮换虚拟 Key">
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

      <div v-if="addOpen" class="mask on" @click="closeChannelDialogs" />
      <div v-if="addOpen" class="modal on" role="dialog" aria-label="新增渠道">
        <div class="mh">新增渠道</div>
        <div class="mb">
          <div class="field">
            <label>名称</label>
            <input v-model="addName" class="inp" maxlength="40">
          </div>
          <div class="share-grid">
            <div class="field">
              <label>模型</label>
              <select v-model="addModel" class="inp">
                <option v-for="name in modelChoices" :key="name" :value="name">{{ name }}</option>
              </select>
            </div>
            <div class="field">
              <label>绑定环境</label>
              <select v-model="addEnv" class="inp">
                <option value="prod">prod</option>
                <option value="staging">staging</option>
                <option value="test">test</option>
                <option value="dev">dev</option>
              </select>
            </div>
          </div>
          <div class="field">
            <label>厂商上限 RPM（每分钟请求数）</label>
            <input v-model.number="addRpm" class="inp" type="number" min="1">
          </div>
          <div class="mut">密钥沿用该模型在 Secret keel-llm-vendors 里的厂商密钥，页面不提交密钥。新渠道单独熔断。</div>
        </div>
        <div class="mf">
          <button class="btn" type="button" :disabled="channelBusy" @click="closeChannelDialogs">取消</button>
          <button v-write class="btn pri" type="button" :disabled="channelBusy" @click="confirmAdd">新增</button>
        </div>
      </div>
      <div v-if="switchOpen" class="mask on" @click="closeChannelDialogs" />
      <div v-if="switchOpen" class="modal on" role="dialog" aria-label="切换 prod 渠道">
        <div class="mh">切换 prod 渠道</div>
        <div class="mb">
          <div class="field">
            <label>prod Key 改走</label>
            <select v-model="switchTo" class="inp" :disabled="prodQwen.length < 2">
              <option v-for="channel in prodQwen" :key="channel.id" :value="channel.id">{{ channel.name }}（{{ channel.model }}，已绑定 {{ channel.bound ?? 0 }}）</option>
            </select>
          </div>
          <div class="mut">{{ switchHint }}</div>
        </div>
        <div class="mf">
          <button class="btn" type="button" :disabled="channelBusy" @click="closeChannelDialogs">取消</button>
          <button v-write class="btn pri" type="button" :disabled="channelBusy || prodQwen.length < 2" @click="confirmSwitch">切换</button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.pbanner { display: block; margin: 10px 0; }
.modal.quota { width: min(480px, 94vw); }
.mask.over { z-index: 70; }
.modal.over { z-index: 80; }
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
.note { font-size: 11.5px; margin-top: 8px; }
h3 .btn { margin-left: 8px; }
.budget-range { accent-color: var(--acc); width: 100%; }
.budget-now { color: var(--white); }
.ops { display: flex; gap: 8px; margin-top: 8px; }
.route-table { margin-top: 0; }
.banner { margin-top: 12px; }
.btn.quota { background: #ffd24a; border-color: #ffd24a; color: #2a2208; font-weight: 600; }
.btn.quota:hover { filter: brightness(1.06); }
</style>
