<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import { deleteAgent, getAgent, getAgentUsage, mergeUsage, retireAgent, type AgentDetail } from '@/api/agents'
import { listDevflowJobs, type DevflowJob } from '@/api/devflow'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { agentStatus, orDash } from '@/utils/format'
import {
  avatarColor,
  avatarLetter,
  canRelease,
  highlightYaml,
  kindLine,
  readyPill,
  resourceLabel,
  seenAgo,
  sourceLabel,
  versionRows,
} from './agentDrawer'
import { cannotChange, layerOf } from './employees'

const TABS = [
  ['ov', '概览'],
  ['yaml', 'agent.yaml'],
  ['inst', '实例与资源'],
  ['ver', '版本'],
  ['dev', '研发记录'],
] as const

type Tab = (typeof TABS)[number][0]

const route = useRoute()
const router = useRouter()
const envStore = useEnvStore()
const detail = ref<AgentDetail | null>(null)
const jobs = ref<DevflowJob[]>([])
const loading = ref(false)
const tab = ref<Tab>('ov')
const shown = ref(false)
const retiring = ref(false)
const retireInput = ref('')
const deleting = ref(false)
const deleteInput = ref('')

const name = computed(() => {
  const value = route.query.drawer
  return typeof value === 'string' ? value : ''
})
const layer = computed(() => layerOf({ name: detail.value?.name || name.value, layer: detail.value?.layer, category: detail.value?.category }))

const rows = computed(() => (detail.value ? versionRows(detail.value) : []))
const releaseOk = computed(() => (detail.value ? canRelease(detail.value) : false))
const yaml = computed(() => (detail.value?.manifestYaml ? highlightYaml(detail.value.manifestYaml) : ''))

const owner = computed(() => {
  const parts = [detail.value?.ownerOrg, detail.value?.ownerUser].filter(Boolean)
  return parts.length ? parts.join(' · ') : '—'
})

const envVersion = computed(() => {
  const parts = [detail.value?.env, detail.value?.version].filter(Boolean)
  return parts.length ? parts.join(' · ') : '—'
})

function yuan(value?: number | null, digits = 2) {
  return value == null ? null : `¥${digits === 0 ? String(value) : value.toFixed(digits)}`
}

let ticket = 0
async function load(agent: string) {
  const current = ++ticket
  const env = envStore.env
  loading.value = true
  detail.value = null
  try {
    const result = await getAgent(agent)
    if (current !== ticket) return
    detail.value = result
  } catch (error) {
    if (current !== ticket) return
    ElMessage.error(`加载智能体失败：${toKeelError(error).message}`)
    return
  } finally {
    if (current === ticket) loading.value = false
  }
  try {
    const usage = await getAgentUsage(env)
    if (current !== ticket || !detail.value) return
    const row = (usage.items ?? []).find((item) => item.name === agent)
    detail.value = mergeUsage(detail.value, row)
  } catch (error) {
    if (current !== ticket) return
    ElMessage.error(`加载用量失败：${toKeelError(error).message}`)
  }
  try {
    const board = await listDevflowJobs()
    if (current !== ticket) return
    jobs.value = (board.items ?? []).filter((job) => job.targetAgent === agent)
  } catch {
    if (current === ticket) jobs.value = []
  }
}

function close() {
  const query = { ...route.query }
  delete query.drawer
  router.replace({ query })
}

function openAgent(agent?: string) {
  if (!agent) return
  router.replace({ query: { ...route.query, drawer: agent } })
}

function viewTraces() {
  router.push('/traces')
}

const retireBusy = ref(false)

async function confirmRetire() {
  const agent = name.value
  const env = detail.value?.env
  if (!agent || retireInput.value !== agent || !env) return
  retireBusy.value = true
  try {
    await retireAgent(agent, env)
    ElMessage.warning(`${agent} 已下线`)
    retiring.value = false
    retireInput.value = ''
    await load(agent)
  } catch (error) {
    ElMessage.error(`下线失败：${toKeelError(error).message}`)
  } finally {
    retireBusy.value = false
  }
}

const deleteBusy = ref(false)

async function confirmDelete() {
  const agent = name.value
  if (!agent || deleteInput.value !== agent || detail.value?.status !== 'RETIRED') return
  deleteBusy.value = true
  try {
    await deleteAgent(agent)
    ElMessage.success(`${agent} 已从注册中心删除`)
    deleting.value = false
    deleteInput.value = ''
    close()
  } catch (error) {
    ElMessage.error(`删除失败：${toKeelError(error).message}`)
  } finally {
    deleteBusy.value = false
  }
}

function onKey(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !name.value) return
  if (retiring.value || deleting.value) {
    retiring.value = false
    deleting.value = false
    return
  }
  close()
}

watch(name, async (agent) => {
  tab.value = 'ov'
  retiring.value = false
  retireInput.value = ''
  deleting.value = false
  deleteInput.value = ''
  shown.value = false
  if (!agent) {
    detail.value = null
    jobs.value = []
    document.body.style.overflow = ''
    return
  }
  document.body.style.overflow = 'hidden'
  await nextTick()
  requestAnimationFrame(() => {
    if (name.value === agent) shown.value = true
  })
  load(agent)
}, { immediate: true })

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <template v-if="name">
      <div class="mask" :class="{ on: shown }" @click="retiring || deleting ? (retiring = deleting = false) : close()" />
      <aside class="drawer" :class="{ on: shown }" role="dialog" aria-label="智能体详情">
        <div class="dh">
          <h3>
            <span class="av" :style="{ background: `${avatarColor(detail?.name || name)}22`, color: avatarColor(detail?.name || name) }">
              {{ avatarLetter(detail?.displayName || name) }}
            </span>
            <span>{{ detail?.displayName || name }}</span>
            <StatusPill v-if="detail" v-bind="agentStatus(detail.status)" />
          </h3>
          <span class="sp" />
          <button class="x" type="button" aria-label="关闭" @click="close">×</button>
        </div>
        <div class="db">
          <div class="dtabs">
            <button v-for="[key, label] in TABS" :key="key" type="button" :class="{ on: tab === key }" :style="key === 'dev' ? { color: 'var(--acc)' } : undefined" @click="tab = key">{{ label }}</button>
          </div>
          <div v-loading="loading" class="dbody">

          <template v-if="detail && tab === 'ov'">
            <div class="kv">
              <span>ID</span><b class="mono">{{ detail.name }}</b>
              <span>类型</span><b>{{ kindLine(detail) }}</b>
              <span>负责人</span><b>{{ owner }}</b>
              <span>环境 / 版本</span><b>{{ envVersion }}</b>
              <template v-if="detail.delegates?.length">
                <span>编排</span>
                <b>
                  <a v-for="item in detail.delegates" :key="item" href="javascript:;" @click.prevent="openAgent(item)">{{ item }}</a>
                </b>
              </template>
            </div>
            <h4>接入自检</h4>
            <ul v-if="detail.selfCheck?.items?.length" class="checks">
              <li v-for="(item, index) in detail.selfCheck.items" :key="index">
                <span class="ic" :class="item.passed === true ? 'ok' : item.passed === false ? 'no' : ''">{{ item.passed === true ? '✓' : item.passed === false ? '✗' : '·' }}</span>
                {{ item.name }}
                <small>{{ item.detail }}</small>
              </li>
            </ul>
            <p v-else class="mut">还没有自检结果</p>
            <h4>绑定资源</h4>
            <div class="kv">
              <span>模型</span>
              <div class="chips">
                <span v-for="(model, index) in detail.models ?? []" :key="model" class="chip">{{ model }} <b>{{ index ? '降级' : '默认' }}</b></span>
                <span v-if="!detail.models?.length" class="mut">—</span>
              </div>
              <span>知识库</span>
              <div class="chips">
                <span v-for="kb in detail.knowledgeBases ?? []" :key="kb" class="chip">{{ kb }}</span>
                <span v-if="!detail.knowledgeBases?.length" class="mut">—</span>
              </div>
              <span>工具</span>
              <div class="chips">
                <span v-for="tool in detail.tools ?? []" :key="tool" class="chip">{{ tool }}</span>
                <span v-if="!detail.tools?.length" class="mut">—</span>
              </div>
              <span>预算</span>
              <div class="chips">
                <span class="chip">{{ yuan(detail.dailyBudgetCny, 0) ? `${yuan(detail.dailyBudgetCny, 0)} / 天` : '—' }}</span>
                <span class="chip">今日 {{ yuan(detail.costCny) ?? '—' }}</span>
              </div>
            </div>
          </template>

          <template v-else-if="detail && tab === 'yaml'">
            <pre v-if="yaml" class="code" v-html="yaml" />
            <p v-else class="empty">还没有 agent.yaml</p>
          </template>

          <template v-else-if="detail && tab === 'inst'">
            <table class="t">
              <thead><tr><th>实例</th><th>探测</th><th>就绪</th><th>最近心跳 / 流量</th></tr></thead>
              <tbody>
                <tr v-if="!detail.instanceList?.length"><td colspan="4" class="empty">尚未部署</td></tr>
                <tr v-for="row in detail.instanceList ?? []" :key="row.instanceId">
                  <td class="mono">{{ row.instanceId }}</td>
                  <td>{{ sourceLabel(row.source) }}</td>
                  <td><span class="pill" :class="readyPill(row.ready, row.source).cls">{{ readyPill(row.ready, row.source).label }}</span></td>
                  <td>{{ seenAgo(row.lastSeenAt) }}</td>
                </tr>
              </tbody>
            </table>
            <h4>替它开通的外部资源</h4>
            <table class="t">
              <thead><tr><th>资源</th><th>标识</th><th>状态</th></tr></thead>
              <tbody>
                <tr v-if="!detail.resources?.length"><td colspan="3" class="empty">还没有开通的外部资源</td></tr>
                <tr v-for="(resource, index) in detail.resources ?? []" :key="`${resource.type}-${index}`">
                  <td>{{ resourceLabel(resource.type) }}</td>
                  <td class="mono">{{ orDash(resource.externalId) }}</td>
                  <td><span class="pill p-mute">{{ orDash(resource.status) }}</span></td>
                </tr>
              </tbody>
            </table>
          </template>

          <template v-else-if="tab === 'dev'">
            <h4>研发记录 <small>生产和改造它的任务</small></h4>
            <table v-if="jobs.length" class="t">
              <tbody>
                <tr v-for="job in jobs" :key="job.jobId" class="click" @click="router.push(`/jobs/${job.jobId}`)">
                  <td class="mono">{{ job.jobId }}</td>
                  <td>{{ job.kind === 'CREATE' ? '新建' : '改造' }}</td>
                  <td>{{ job.mode }}</td>
                  <td>{{ job.status }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else class="mut">人工编写，没有研发任务记录</p>
            <h4>不能做的事</h4>
            <ul class="forbid">
              <li>修改自己或上级（{{ cannotChange(layer) }}）</li>
              <li>授予任何工具或知识库权限；读隐藏考题内容</li>
            </ul>
          </template>

          <template v-else-if="detail && tab === 'ver'">
            <table class="t">
              <thead><tr><th>版本</th><th>环境</th><th>变更</th><th>评测分</th><th>门禁</th><th>时间</th></tr></thead>
              <tbody>
                <tr v-if="!rows.length"><td colspan="6" class="empty">还没有版本</td></tr>
                <tr v-for="row in rows" :key="`${row.version}-${row.env}-${row.time}`">
                  <td class="mono">{{ row.version }}</td>
                  <td>{{ row.env }}</td>
                  <td>{{ row.change }}</td>
                  <td class="mono">{{ row.score }}</td>
                  <td>
                    <span v-if="row.gate === true" class="pill p-ok">通过</span>
                    <span v-else-if="row.gate === false" class="pill p-bad">未通过</span>
                    <span v-else>—</span>
                  </td>
                  <td class="mono">{{ row.time }}</td>
                </tr>
              </tbody>
            </table>
          </template>
          </div>
        </div>
        <div class="df">
          <template v-if="detail?.status === 'RETIRED'">
            <span class="mut retired">已下线，历史追踪、评测、审计可查</span>
            <button v-write class="btn danger" type="button" @click="deleting = true">删除</button>
          </template>
          <template v-else>
            <RouterLink v-if="detail && detail.status !== 'DRAFT'" class="btn" :to="`/agents/${encodeURIComponent(name)}/debug`">调试</RouterLink>
            <button v-if="layer !== 'meta'" v-write class="btn" type="button" @click="router.push({ path: '/agents/new', query: { method: 'devflow', kind: 'CHANGE', agent: name } })">发起改造任务</button>
            <span v-else class="mut">元智能体只能由人直接改代码。</span>
            <button class="btn ghost" type="button" @click="viewTraces">查看链路</button>
            <button v-if="detail" v-write class="btn danger" type="button" @click="retiring = true">下线</button>
            <button
              v-if="detail?.env === 'staging'"
              v-write
              class="btn pri"
              type="button"
              :disabled="!releaseOk"
              :title="releaseOk ? undefined : '门禁未通过或未就绪'"
              @click="ElMessage.info('发布到 prod 尚未接入')"
            >发布到 prod</button>
          </template>
        </div>
      </aside>

      <div v-if="retiring" class="modal on" role="dialog" aria-label="下线智能体">
        <div class="mh">下线智能体</div>
        <div class="mb">
          <p>下线后将<b>吊销薄网关虚拟 Key、删除 Secret</b>，历史追踪、评测和审计保留。正在跑的实例要另行停掉。下线后才能从注册中心删除。</p>
          <div class="field">
            <label>输入智能体 ID <b class="mono">{{ name }}</b> 确认</label>
            <input v-model="retireInput" class="inp" autocomplete="off">
          </div>
        </div>
        <div class="mf">
          <button class="btn" type="button" @click="retiring = false">取消</button>
          <button v-write class="btn danger" type="button" :disabled="retireInput !== name || retireBusy" @click="confirmRetire">{{ retireBusy ? '下线中…' : '确认下线' }}</button>
        </div>
      </div>

      <div v-if="deleting" class="modal on" role="dialog" aria-label="删除智能体">
        <div class="mh">删除智能体</div>
        <div class="mb">
          <p>删除后它从注册中心的列表、搜索和谱系里消失，<b>不能恢复</b>。</p>
          <p class="mut">审计记录（只增不删）和 Langfuse 里的链路、评测保留到各自保留期。名称不会释放，之后不能再注册同名智能体。</p>
          <div class="field">
            <label>输入智能体 ID <b class="mono">{{ name }}</b> 确认</label>
            <input v-model="deleteInput" class="inp" autocomplete="off">
          </div>
        </div>
        <div class="mf">
          <button class="btn" type="button" @click="deleting = false">取消</button>
          <button v-write class="btn danger" type="button" :disabled="deleteInput !== name || deleteBusy" @click="confirmDelete">{{ deleteBusy ? '删除中…' : '确认删除' }}</button>
        </div>
      </div>
    </template>
  </Teleport>
</template>
