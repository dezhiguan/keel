<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import LogicalRouting from './LogicalRouting.vue'
import RouteDiagnostic from './RouteDiagnostic.vue'
import {
  addModelChannel,
  getModelGateway,
  probeModel,
  probeModelChannel,
  rotateModelKey,
  simulateChannelFailures,
  switchProdChannel,
  updateModelBudget,
  type ModelGateway,
} from '@/api/models'
import { breakerLabel } from './channelBreaker'
import {
  channelPills,
  channelsForEnv,
  coverageText,
  initialDraft,
  neededModels,
  shareBlocked,
  shareLines,
  type ChannelDraft,
} from './channelShare'
import { mergeGateway, prefetchAfter, sectionForTab, type GatewaySection } from './gatewayLoad'
import { billClock, billStatusLabel, limitLabel, routeReason } from './modelBill'
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
const loaded = ref<Record<GatewaySection, boolean>>({ channels: false, keys: false, routes: false })
const failed = ref<GatewaySection | null>(null)
let ticket = 0
const inflight = new Map<string, Promise<void>>()
const saving = ref(false)
const selectedAlias = ref('')
const nextBudget = ref(10)
const originalChannels = ref('')
const nextTpm = ref<number | ''>('')
const nextConcurrency = ref<number | ''>('')
const channelDraft = ref<ChannelDraft>({})
const rotating = ref<GatewayKey | null>(null)
const rotated = ref<{ key: string; version: number } | null>(null)
const rotationBusy = ref(false)
const modelPage = ref(1)
const modelSize = ref<PageSize>(10)
const keyPage = ref(1)
const keySize = ref<PageSize>(10)
const diagnosticRevision = ref(0)
const nowMs = ref(Date.now())
const channelBusy = ref(false)
const addOpen = ref(false)
const switchOpen = ref(false)
const addName = ref('百炼·主账号B·生产空间')
const addModel = ref('')
const addEnv = ref<'prod' | 'staging'>('staging')
const addRpm = ref(1200)
const switchTo = ref('')
let clock = 0

const models = computed(() => data.value?.models ?? [])
const keys = computed(() => data.value?.keys ?? [])
const bills = computed(() => data.value?.bills ?? [])
const failedBills = computed(() => bills.value.filter((bill) => bill.status === 'FAILED'))
const simulated = ref<{ time: string; alias: string; tone: string; result: string; reason: string }[]>([])
const admin = computed(() => userStore.user?.platformRole === 'ADMIN')
const quotaOpen = ref(false)
const selectedKey = computed(() => keys.value.find((key) => key.alias === selectedAlias.value) ?? null)
const keyRows = computed(() => keys.value.slice((keyPage.value - 1) * keySize.value, keyPage.value * keySize.value))
const logicalRoutes = computed(() => data.value?.logicalRoutes ?? [])

function keyAllows(key: GatewayKey, model: string) {
  const visible = channelsForEnv(liveChannels.value, key.env)
  const selected = selectedModelsOf(key, visible)
  return selected.length ? selected.includes(model) : (key.models ?? []).includes(model)
}

function selectedModelsOf(key: GatewayKey, visible: ReturnType<typeof channelsForEnv>) {
  const draft = initialDraft(key, visible)
  return visible.filter((channel) => Object.prototype.hasOwnProperty.call(draft, channel.id) && channel.model).map((channel) => channel.model as string)
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

const quotaChannels = computed(() => channelsForEnv(liveChannels.value, selectedKey.value?.env))
const quotaCoverage = computed(() => coverageText(selectedKey.value?.env, logicalRoutes.value, channelDraft.value, quotaChannels.value))
const quotaShares = computed(() => shareLines(channelDraft.value, quotaChannels.value, keys.value, selectedKey.value?.alias))
const quotaOver = computed(() => quotaShares.value.some((line) => line.over))
const channelsChanged = computed(() => JSON.stringify(channelDraft.value) !== originalChannels.value)
const quotaSaveBlocked = computed(() => channelsChanged.value && shareBlocked(channelDraft.value, quotaChannels.value, keys.value, selectedKey.value?.alias))

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
  nextBudget.value = key.dailyBudgetCny ?? 10
  nextTpm.value = key.tpm ?? ''
  nextConcurrency.value = key.concurrency ?? ''
  channelDraft.value = initialDraft(key, channelsForEnv(liveChannels.value, key.env))
  originalChannels.value = JSON.stringify(channelDraft.value)
}

function quotaLimits(key: GatewayKey) {
  if (!key.shares?.length && key.rpm == null && key.tpm == null && key.concurrency == null) return '未设限'
  const rpm = key.shares?.length ? '按渠道' : key.rpm == null ? '未设限' : key.rpm.toLocaleString('zh-CN')
  const tpm = key.tpm == null ? '未设限' : key.tpm.toLocaleString('zh-CN')
  const concurrency = key.concurrency == null ? '未设限' : key.concurrency.toLocaleString('zh-CN')
  return `${rpm} / ${tpm} / ${concurrency}`
}

function budgetProgress(key: GatewayKey) {
  if (key.dailyBudgetCny == null || key.dailyBudgetCny <= 0 || key.spentCny == null || key.reservedCny == null) return null
  return Math.max(0, ((key.spentCny + key.reservedCny) / key.dailyBudgetCny) * 100)
}

function budgetColor(key: GatewayKey) {
  const percent = budgetProgress(key) ?? 0
  return percent >= 100 ? 'var(--bad)' : percent >= 80 ? 'var(--warn)' : 'var(--teal)'
}

function toggleChannel(id: string, on: boolean) {
  const next = { ...channelDraft.value }
  if (on) next[id] = 50
  else delete next[id]
  channelDraft.value = next
}

function editShare(id: string, raw: string) {
  if (!Object.prototype.hasOwnProperty.call(channelDraft.value, id)) return
  const next = { ...channelDraft.value }
  next[id] = raw === '' ? '' : Number(raw)
  channelDraft.value = next
}

function onChannelToggle(id: string, event: Event) {
  toggleChannel(id, (event.target as HTMLInputElement).checked)
}

function onShareInput(id: string, event: Event) {
  editShare(id, (event.target as HTMLInputElement).value)
}

function keyGap(key: GatewayKey) {
  return neededModels(logicalRoutes.value, key.env).filter((model) => !keyAllows(key, model))
}

function openQuota(key: GatewayKey) {
  selectKey(key)
  quotaOpen.value = true
}

function closeQuota() {
  if (saving.value) return
  quotaOpen.value = false
}

function sectionPending(section: GatewaySection) {
  return !loaded.value[section] && failed.value !== section
}

function settlePages() {
  clamp(modelPage, modelSize.value, usingLive.value ? visibleLive.value.length : channels.value.length)
  clamp(keyPage, keySize.value, keys.value.length)
}

function settleSelection() {
  settlePages()
  const current = keys.value.find((key) => key.alias === selectedAlias.value)
  if (current) selectKey(current)
  else quotaOpen.value = false

}

async function fetchSection(section: GatewaySection, mine: number, resync: boolean) {
  const key = `${mine}:${section}`
  const existing = inflight.get(key)
  if (existing) {
    await existing
    return
  }
  const job = (async () => {
    if (failed.value === section) failed.value = null
    try {
      const part = await getModelGateway({ env: envStore.env, range: '24h', section })
      if (mine !== ticket) return
      data.value = mergeGateway(data.value, part)
      loaded.value = { ...loaded.value, [section]: true }
      if (resync) settleSelection()
      else settlePages()
    } catch (error) {
      if (mine === ticket && sectionForTab(tab.value) === section) {
        failed.value = section
        ElMessage.error(`加载模型网关失败：${toKeelError(error).message}`)
      }
    } finally {
      inflight.delete(key)
    }
  })()
  inflight.set(key, job)
  await job
}

async function load() {
  const mine = ++ticket
  data.value = null
  loaded.value = { channels: false, keys: false, routes: false }
  failed.value = null
  const first = sectionForTab(tab.value)
  await fetchSection(first, mine, true)
  if (mine !== ticket) return
  for (const section of prefetchAfter(first)) {
    if (mine !== ticket) return
    if (!loaded.value[section]) await fetchSection(section, mine, false)
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

async function saveQuota() {
  const key = selectedKey.value
  if (!key?.alias || key.status === 'BLOCKED' || !admin.value || quotaSaveBlocked.value) return
  saving.value = true
  try {
    const limit = (value: number | '', label: string) => {
      if (value === '') return null
      if (!Number.isInteger(value) || value <= 0 || value > 2147483647) throw new Error(`${label} 须为正整数，留空表示不限制`)
      return value
    }
    if (!Number.isInteger(nextBudget.value) || nextBudget.value < 10 || nextBudget.value > 200 || nextBudget.value % 5 !== 0) throw new Error('日预算须在 ¥10 到 ¥200 之间，且为 5 的倍数')
    const channels = channelsChanged.value ? Object.entries(channelDraft.value).map(([id, rpm]) => {
      if (rpm === '' || !Number.isInteger(rpm) || rpm < 1) throw new Error('渠道 RPM 须为正整数')
      return { id, rpm }
    }) : undefined
    await updateModelBudget(key.alias, {
      dailyBudgetCny: nextBudget.value,
      tpm: limit(nextTpm.value, 'TPM'),
      concurrency: limit(nextConcurrency.value, '并发'),
      channels,
    })
    ElMessage.success(`已更新 ${key.alias} 的配额和日预算`)
    quotaOpen.value = false
    await load()
  } catch (error) {
    const message = error instanceof Error ? error.message : ''
    ElMessage.error(`调整配额失败：${message.includes('须为正整数') || message.startsWith('日预算须') ? message : toKeelError(error).message}`)
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
watch(tab, (value) => {
  const section = sectionForTab(value)
  if (!loaded.value[section]) void fetchSection(section, ticket, false)
})
</script>

<template>
  <div class="models">
    <div class="vh">
      <h2>模型网关</h2>
    </div>
    <div class="dtabs">
      <button type="button" :class="{ on: tab === 'model' }" @click="tab = 'model'">模型与渠道</button>
      <button type="button" :class="{ on: tab === 'key' }" @click="tab = 'key'">Key 与配额</button>
      <button type="button" :class="{ on: tab === 'route' }" @click="tab = 'route'">路由</button>
    </div>

    <div v-if="tab === 'model'" v-loading="sectionPending('channels')" class="tab-body">
      <template v-if="loaded.channels">
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
      </template>
    </div>

    <div v-if="tab === 'key'" v-loading="sectionPending('keys')" class="tab-body">
      <template v-if="loaded.keys">
      <div class="card">
        <h3>Key 与配额 <small>按智能体和环境管理调用权限、限额与日预算</small></h3>
          <div class="mut legend">RPM = 每分钟请求数 · TPM = 每分钟 Token 数（输入 + 输出）· 并发 = 同时进行中的请求数</div>
          <div class="key-table-scroll">
          <table class="t key-table">
            <colgroup>
              <col style="width: 24%">
              <col style="width: 7%">
              <col style="width: 25%">
              <col style="width: 15%">
              <col style="width: 9%">
              <col style="width: 12%">
              <col style="width: 8%">
            </colgroup>
            <thead>
              <tr>
                <th>智能体 / Key</th>
                <th>环境</th>
                <th>已结算 + 预留 / 日预算</th>
                <th>允许渠道</th>
                <th>路由</th>
                <th title="RPM / TPM / 并发：每分钟请求数 / 每分钟 Token 数 / 同时进行中的请求数">限流</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="key in keyRows" :key="key.alias">
                <td class="key-identity">
                  <div class="key-agent" :title="key.agent">{{ key.agent || '—' }}</div>
                  <div class="mono mut key-alias" :title="`${key.alias} · ${key.version == null ? '—' : `v${key.version}`}`">{{ key.alias }} · {{ key.version == null ? '—' : `v${key.version}` }}</div>
                </td>
                <td><span class="pill nd p-mute">{{ key.env || '—' }}</span></td>
                <td class="key-budget">
                  <div class="budget-amounts mono"><span>{{ yuan(key.spentCny) }} + {{ yuan(key.reservedCny) }}</span><span class="mut">{{ yuan(key.dailyBudgetCny) }}</span></div>
                  <div v-if="budgetProgress(key) != null" class="bar" role="progressbar" aria-label="Key 日预算使用率" :aria-valuenow="Math.min(100, Math.round(budgetProgress(key)!))" :aria-valuetext="`${yuan(key.spentCny)} + ${yuan(key.reservedCny)} / ${yuan(key.dailyBudgetCny)}`" aria-valuemin="0" aria-valuemax="100">
                    <i :style="{ width: `${Math.min(100, budgetProgress(key)!)}%`, background: budgetColor(key) }" />
                  </div>
                </td>
                <td>
                  <div class="key-channels">
                    <span v-for="pill in channelPills(key, liveChannels)" :key="pill" class="pill nd p-mute share" :title="pill">{{ pill.replace(/^DashScope(?= ·|$)/, 'Qwen') }}</span>
                    <span v-if="!channelPills(key, liveChannels).length" class="mut">—</span>
                  </div>
                </td>
                <td>
                  <span v-if="keyGap(key).length" class="pill p-bad" :title="`缺少模型授权：${keyGap(key).join('、')}`" :aria-label="`缺少模型授权：${keyGap(key).join('、')}`">缺授权</span>
                  <span v-else class="pill p-ok">已覆盖</span>
                </td>
                <td><div class="mono key-limits" :title="`RPM / TPM / 并发：${quotaLimits(key)}`">{{ quotaLimits(key) }}</div></td>
                <td>
                  <button class="btn sm quota" type="button" @click="openQuota(key)">配额</button>
                </td>
              </tr>
              <tr v-if="!keyRows.length"><td colspan="7" class="empty">这个环境还没有虚拟 Key</td></tr>
            </tbody>
          </table>
          </div>
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
              <td class="mut">{{ routeReason(bill.routeVersion) }} · {{ bill.model || '—' }} · {{ yuan(bill.costCny) }}</td>
            </tr>
            <tr v-if="!simulated.length && !failedBills.length"><td colspan="4" class="mut">暂无读取到的真实拒绝记录；可用「模拟一次请求」试算</td></tr>
          </tbody>
        </table>
      </div>
      </template>
    </div>

    <div v-if="tab === 'route'" v-loading="sectionPending('routes')" class="tab-body">
      <template v-if="loaded.routes">
      <LogicalRouting @keys="tab = 'key'" @changed="load(); diagnosticRevision++" />
      <RouteDiagnostic :keys="keys" :logical-routes="logicalRoutes" :revision="diagnosticRevision" />
      </template>
    </div>

    <Teleport to="body">
      <div v-if="quotaOpen && selectedKey" class="mask on" @click="closeQuota" />
      <div v-if="quotaOpen && selectedKey" class="modal quota on" role="dialog" aria-label="分配份额">
        <div class="mh">分配份额 · <span class="mono">{{ selectedKey.alias }}</span></div>
        <div class="mb">
          <div class="field">
            <label>允许的渠道（只列同环境）</label>
            <div v-for="channel in quotaChannels" :key="channel.id" class="ch-row">
              <label>
                <input
                  type="checkbox"
                  :disabled="!admin || selectedKey.status === 'BLOCKED'"
                  :checked="Object.prototype.hasOwnProperty.call(channelDraft, channel.id)"
                  @change="onChannelToggle(channel.id, $event)"
                >
                {{ channel.name || '渠道' }}
                <span class="mut">{{ channel.model }}</span>
              </label>
              <input
                class="inp rpm"
                type="number"
                min="1"
                step="1"
                placeholder="RPM"
                title="RPM：每分钟请求数"
                :disabled="!admin || selectedKey.status === 'BLOCKED' || !Object.prototype.hasOwnProperty.call(channelDraft, channel.id)"
                :value="channelDraft[channel.id] ?? ''"
                @input="onShareInput(channel.id, $event)"
              >
            </div>
            <div v-if="!quotaChannels.length" class="mut">这个环境还没有渠道</div>
          </div>
          <div class="share-grid">
            <div class="field">
              <label for="key-quota-tpm">TPM（每分钟 Token 数，输入 + 输出）</label>
              <input id="key-quota-tpm" v-model.number="nextTpm" class="inp" type="number" min="1" step="1" placeholder="留空表示不限制" :disabled="!admin || selectedKey.status === 'BLOCKED'">
            </div>
            <div class="field">
              <label for="key-quota-concurrency">并发</label>
              <input id="key-quota-concurrency" v-model.number="nextConcurrency" class="inp" type="number" min="1" step="1" placeholder="留空表示不限制" :disabled="!admin || selectedKey.status === 'BLOCKED'">
            </div>
          </div>
          <div class="field quota-budget">
            <label for="key-quota-budget">日预算：<b>¥{{ nextBudget }}</b>（今日已用 {{ yuan(selectedKey.spentCny) }}）</label>
            <input id="key-quota-budget" v-model.number="nextBudget" type="range" min="10" max="200" step="5" :disabled="!admin || selectedKey.status === 'BLOCKED' || saving">
            <p class="mut note">保存后同步更新该 Key 的日预算，并记录审计。</p>
          </div>
          <div class="pbanner" :class="quotaCoverage.tone">
            {{ quotaCoverage.text }}
            <div v-if="quotaCoverage.detail" class="mut">{{ quotaCoverage.detail }}</div>
          </div>
          <div v-if="!Object.keys(channelDraft).length" class="pbanner bad">至少允许一个渠道。</div>
          <div v-else class="pbanner" :class="quotaOver ? 'bad' : 'ok'">
            <div v-for="line in quotaShares" :key="line.id">{{ line.text }}</div>
            <span class="mut">{{ quotaOver ? '请降低份额、申请提额，或改用独立子业务空间。' : '超出份额的请求在网关返回 LLM_RATE_LIMITED，不打到厂商。' }}</span>
          </div>
        </div>
        <div class="mf">
          <button class="btn" type="button" :disabled="saving" @click="closeQuota">关闭</button>
          <button v-if="admin && selectedKey.status !== 'BLOCKED'" v-write class="btn" type="button" @click="openRotation(selectedKey)">轮换</button>
          <button v-if="admin && selectedKey.status !== 'BLOCKED'" v-write class="btn pri" type="button" :disabled="saving || quotaSaveBlocked" @click="saveQuota">保存</button>
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
.key-table-scroll { overflow-x: auto; }
.key-table { table-layout: fixed; width: 100%; min-width: 880px; }
.key-table th, .key-table td { padding: 10px 8px; }
.key-table th { white-space: nowrap; }
.key-agent, .key-alias, .key-limits { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.key-agent { font-weight: 600; }
.key-alias { font-size: 11px; margin-top: 3px; }
.key-channels { display: flex; flex-wrap: wrap; gap: 4px; }
.key-channels .share { min-width: 0; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin: 0; padding: 2px 6px; font-size: 11px; }
.key-table .pill { white-space: nowrap; }
.budget-amounts { display: flex; justify-content: space-between; gap: 8px; white-space: nowrap; margin-bottom: 5px; font-size: 12px; }
.quota-budget { border-top: 1px solid var(--line); padding-top: 16px; margin-top: 10px; }
.quota-budget b { color: var(--text); }
.quota-budget input[type="range"] { width: 100%; accent-color: var(--acc); }
.pbanner { display: block; margin: 10px 0; }
.modal.quota { width: min(560px, 94vw); }
.modal.quota .mb { max-height: min(70vh, 640px); overflow: auto; }
.mask.over { z-index: 70; }
.modal.over { z-index: 80; }
.bar { min-width: 90px; }
th small { font-weight: 400; }
.legend { font-size: 11.5px; margin: -6px 0 8px; }
.alloc { font-size: 11.5px; }
.share { margin: 1px 2px; }
.ch-row { display: flex; align-items: center; gap: 10px; padding: 5px 0; border-bottom: 1px solid #172235; }
.ch-row label { display: flex; gap: 8px; align-items: center; flex: 1; margin: 0; color: var(--text); font-size: 12.8px; cursor: pointer; }
.ch-row .mut { font-size: 11.5px; }
.ch-row .rpm { width: 90px; flex: none; }
.share-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.note { font-size: 11.5px; margin-top: 8px; }
h3 .btn { margin-left: 8px; }
.ops { display: flex; gap: 8px; margin-top: 8px; }
.route-table { margin-top: 0; }
.tab-body { min-height: 240px; }
.banner { margin-top: 12px; }
.btn.quota { background: var(--acc); border-color: var(--acc); color: #1d0e05; font-weight: 700; }
.btn.quota:hover { filter: brightness(1.08); }
</style>
