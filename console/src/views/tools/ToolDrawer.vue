<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import ToolStatusPill from './ToolStatusPill.vue'
import {
  deprecateTool,
  getTool,
  listTools,
  publishToolVersion,
  registerTool,
  retireTool,
  type ToolDetail,
  type ToolSummary,
} from '@/api/tools'
import { toKeelError } from '@/api/http'
import { RISK, agentStatus } from '@/utils/format'
import {
  BREAK_DEADLINE,
  DEPRECATE_DEADLINE,
  VERSION_KINDS,
  breakHint,
  nextVersion,
  otherDependents,
  prodDependents,
  raisedRisk,
  replacementId,
  versionRows,
  versionTime,
  type RiskLevel,
  type VersionKind,
} from './toolDrawer'

const ACCESS = { READ: '读', WRITE: '写', EXEC: '执行' } as const
const SCOPE = { PRIVATE: '私有', SHARED: '共享' } as const

const emit = defineEmits<{ changed: [] }>()

const route = useRoute()
const router = useRouter()
const tool = ref<ToolDetail | null>(null)
const loading = ref(false)
const shown = ref(false)
const modal = ref<'' | 'block' | 'retire' | 'deprecate' | 'version'>('')
const catalog = ref<ToolSummary[]>([])
const replacedBy = ref('')
const deadline = ref(DEPRECATE_DEADLINE)
const versionKind = ref<VersionKind>('desc')
const versionBusy = ref(false)
const checks = ref<{ agent: string; state: '' | 'ok'; detail: string }[]>([])

const name = computed(() => {
  const value = route.query.tool
  return typeof value === 'string' ? value : ''
})

const history = computed(() => versionRows(tool.value?.versions))
const prodDeps = computed(() => prodDependents(tool.value?.dependents))
const otherDeps = computed(() => otherDependents(tool.value?.dependents))
const replacements = computed(() => {
  const current = tool.value?.name
  const online = catalog.value.flatMap((item) => (item.status === 'ONLINE' && item.name && item.name !== current ? [item.name] : []))
  return current ? [...online, `${current}.v2（新建）`] : online
})

const owner = computed(() => tool.value?.ownerAgent || tool.value?.ownerOrg || '—')

function schemaOf(current: ToolDetail): Record<string, unknown> {
  const schema = current.schemaJson
  if (schema && typeof schema === 'object' && !Array.isArray(schema)) return schema as Record<string, unknown>
  return { type: 'object' }
}

let ticket = 0
async function load(toolName: string) {
  const current = ++ticket
  loading.value = true
  try {
    const result = await getTool(toolName)
    if (current !== ticket) return
    tool.value = result
  } catch (error) {
    if (current !== ticket) return
    tool.value = null
    ElMessage.error(`加载工具失败：${toKeelError(error).message}`)
  } finally {
    if (current === ticket) loading.value = false
  }
}

async function reload() {
  if (!name.value) return
  await load(name.value)
  emit('changed')
}

function close() {
  const query = { ...route.query }
  delete query.tool
  router.replace({ query })
}

function openAgent(agent?: string) {
  if (!agent) return
  router.push({ path: '/agents', query: { drawer: agent } })
}

function openRetire() {
  modal.value = prodDeps.value.length ? 'block' : 'retire'
}

async function openDeprecate() {
  replacedBy.value = ''
  deadline.value = DEPRECATE_DEADLINE
  modal.value = 'deprecate'
  try {
    const page = await listTools({ scope: 'all', page: 1, size: 100 })
    catalog.value = page.items ?? []
  } catch (error) {
    catalog.value = []
    ElMessage.error(`加载替代工具失败：${toKeelError(error).message}`)
  }
}

function openVersion() {
  versionKind.value = 'desc'
  checks.value = []
  versionBusy.value = false
  modal.value = 'version'
}

function closeModal() {
  if (versionBusy.value) return
  modal.value = ''
}

async function confirmRetire() {
  const current = tool.value
  if (!current?.name) return
  try {
    await retireTool(current.name)
    ElMessage.warning(`${current.name} 已下线`)
    emit('changed')
    modal.value = ''
    close()
  } catch (error) {
    const keel = toKeelError(error)
    if (keel.code === 'TOOL_HAS_PROD_DEPENDENTS') {
      modal.value = 'block'
      return
    }
    ElMessage.error(keel.message)
  }
}

async function confirmDeprecate() {
  const current = tool.value
  if (!current?.name) return
  const replaced = replacementId(replacedBy.value.trim())
  if (!replaced || !deadline.value) {
    ElMessage.warning(replaced ? '请选择截止日期' : '请选择替代工具')
    return
  }
  try {
    await deprecateTool(current.name, replaced, deadline.value)
    modal.value = ''
    ElMessage.warning(`${current.name} 已废弃，已通知依赖方`)
    await reload()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

async function publishBreaking(current: ToolDetail) {
  const nid = `${current.name}.v2`
  try {
    await registerTool({
      name: nid,
      description: `${current.description || current.name}（新版）`,
      scope: current.scope ?? 'PRIVATE',
      access: current.access ?? 'READ',
      risk: current.risk ?? 'LOW',
      provider: current.provider || `mcp://${nid}`,
      ownerAgent: current.ownerAgent || undefined,
      schemaJson: schemaOf(current),
    })
  } catch (error) {
    const keel = toKeelError(error)
    if (!keel.message.includes('已存在')) throw error
  }
  await deprecateTool(current.name!, nid, BREAK_DEADLINE)
  modal.value = ''
  ElMessage.warning(`已创建 ${nid}，原工具进入废弃期`)
  await reload()
}

async function publishRisk(current: ToolDetail) {
  const risk = raisedRisk(current.risk) as RiskLevel
  await publishToolVersion(current.name!, {
    version: nextVersion(current.version),
    description: current.description,
    schemaJson: schemaOf(current),
    risk,
    breaking: false,
  })
  modal.value = ''
  ElMessage.warning(`${current.name} 风险已调为 ${RISK[risk].label}，已通知依赖方`)
  await reload()
}

async function publishDescription(current: ToolDetail) {
  const version = nextVersion(current.version)
  const deps = (current.dependents ?? []).flatMap((item) => (item.agent ? [item.agent] : []))
  checks.value = deps.map((agent) => ({ agent, state: '', detail: '' }))
  const result = await publishToolVersion(current.name!, {
    version,
    description: current.description,
    schemaJson: schemaOf(current),
    breaking: false,
  })
  const triggered = result.triggeredRegressions ?? []
  for (let index = 0; index < deps.length; index += 1) {
    checks.value[index] = { agent: deps[index], state: 'ok', detail: '已触发' }
    await new Promise((resolve) => setTimeout(resolve, 220))
  }
  modal.value = ''
  ElMessage.success(triggered.length ? `已发布，将回归：${triggered.join('、')}` : `没有依赖方，${current.name} ${version} 已上线`)
  await reload()
}

async function submitVersion() {
  const current = tool.value
  if (!current?.name || versionBusy.value) return
  if (versionKind.value === 'impl') {
    modal.value = ''
    ElMessage.success(`${current.name} 实现已更新，接口不变`)
    return
  }
  versionBusy.value = true
  try {
    if (versionKind.value === 'break') await publishBreaking(current)
    else if (versionKind.value === 'risk') await publishRisk(current)
    else await publishDescription(current)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    versionBusy.value = false
  }
}

function onKey(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !name.value) return
  if (modal.value) {
    closeModal()
    return
  }
  close()
}

watch(name, async (toolName) => {
  modal.value = ''
  shown.value = false
  if (!toolName) {
    tool.value = null
    document.body.style.overflow = ''
    return
  }
  document.body.style.overflow = 'hidden'
  await nextTick()
  requestAnimationFrame(() => {
    if (name.value === toolName) shown.value = true
  })
  load(toolName)
}, { immediate: true })

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  if (typeof route.query.drawer !== 'string') document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <template v-if="name">
      <div class="mask" :class="{ on: shown }" @click="modal ? closeModal() : close()" />
      <aside class="drawer" :class="{ on: shown }" role="dialog" aria-label="工具详情">
        <div class="dh">
          <h3>
            <span class="mono">{{ tool?.name || name }}</span>
            <ToolStatusPill v-if="tool" :status="tool.status" />
          </h3>
          <span class="sp" />
          <button class="x" type="button" aria-label="关闭" @click="close">×</button>
        </div>
        <div v-loading="loading" class="db">
          <template v-if="tool">
            <div class="kv">
              <span>说明</span><b>{{ tool.description || '—' }}</b>
              <span>范围 / 所有者</span><b>{{ tool.scope ? SCOPE[tool.scope] : '—' }} · {{ owner }}</b>
              <span>读写 / 风险</span>
              <b>
                {{ tool.access ? ACCESS[tool.access] : '—' }}
                ·
                <span v-if="tool.risk" class="pill nd" :class="RISK[tool.risk].cls">{{ RISK[tool.risk].label }}</span>
                <template v-else>—</template>
              </b>
              <span>审批策略</span><b>{{ tool.approvalPolicy || '—' }}</b>
              <span>当前版本</span><b class="mono">{{ tool.version || '—' }}</b>
              <template v-if="tool.status === 'DEPRECATED'">
                <span>替代工具</span><b class="mono">{{ tool.replacedBy }}（截止 {{ tool.deprecateDeadline }}）</b>
              </template>
            </div>

            <h4>依赖方（manifest 声明 + 30 天调用）</h4>
            <table v-if="tool.dependents?.length" class="t">
              <thead><tr><th>智能体</th><th>环境</th><th>状态</th></tr></thead>
              <tbody>
                <tr
                  v-for="item in tool.dependents"
                  :key="`${item.agent}-${item.env}`"
                  class="click"
                  @click="openAgent(item.agent)"
                >
                  <td>{{ item.agent }}</td>
                  <td>{{ item.env }}</td>
                  <td><StatusPill v-bind="agentStatus(item.agentStatus)" /></td>
                </tr>
              </tbody>
            </table>
            <p v-else class="mut">没有智能体在用</p>

            <h4>版本历史</h4>
            <table class="t">
              <thead><tr><th>版本</th><th>变更</th><th>时间</th></tr></thead>
              <tbody>
                <tr v-if="!history.length"><td colspan="3" class="empty">还没有版本</td></tr>
                <tr v-for="row in history" :key="`${row.version}-${row.at}`">
                  <td class="mono">{{ row.version }}</td>
                  <td>{{ row.change || '—' }}</td>
                  <td class="mono">{{ versionTime(row.at) }}</td>
                </tr>
              </tbody>
            </table>
            <p class="mut" style="font-size: 12px; margin-top: 14px">
              生命周期：已注册 → 上线 → 废弃 → 下线。改描述或参数会触发所有依赖方回归评测；破坏兼容必须发新名字；有 prod 依赖不能直接下线。
            </p>
          </template>
        </div>
        <div v-if="tool && tool.status !== 'RETIRED'" class="df">
          <button v-write class="btn danger" type="button" @click="openRetire">下线</button>
          <button v-if="tool.status !== 'DEPRECATED'" class="btn" type="button" @click="openDeprecate">废弃</button>
          <button v-write class="btn pri" type="button" @click="openVersion">发布新版本</button>
        </div>
      </aside>

      <div v-if="modal === 'block'" class="modal on" role="dialog" aria-label="不能下线">
        <div class="mh">不能下线</div>
        <div class="mb">
          <p><b class="mono">{{ tool?.name }}</b> 仍被 prod 智能体依赖：
            <span v-for="item in prodDeps" :key="item.agent" class="chip">{{ item.agent }}</span>
          </p>
          <p class="mut" style="margin-top: 8px">有 prod 依赖的工具只能先<b>废弃</b>，等依赖方迁移完再下线。</p>
        </div>
        <div class="mf">
          <button class="btn" type="button" @click="closeModal">知道了</button>
          <button v-if="tool?.status !== 'DEPRECATED'" v-write class="btn pri" type="button" @click="openDeprecate">改为废弃</button>
        </div>
      </div>

      <div v-if="modal === 'retire'" class="modal on" role="dialog" aria-label="下线工具">
        <div class="mh">下线工具 <span class="mono">{{ tool?.name }}</span></div>
        <div class="mb">
          <p v-if="otherDeps.length">
            staging 依赖方 {{ otherDeps.map((item) => item.agent).join('、') }} 之后调用会返回 <code class="mono">TOOL_RETIRED</code>。
          </p>
          <p v-else>没有智能体依赖它，可以安全下线。</p>
          <p class="mut" style="margin-top: 6px">注册记录归档不删除，历史调用在审计中可查。</p>
        </div>
        <div class="mf">
          <button class="btn" type="button" @click="closeModal">取消</button>
          <button v-write class="btn danger" type="button" @click="confirmRetire">确认下线</button>
        </div>
      </div>

      <div v-if="modal === 'deprecate'" class="modal on" role="dialog" aria-label="废弃工具">
        <div class="mh">废弃工具 <span class="mono">{{ tool?.name }}</span></div>
        <div class="mb">
          <p>废弃期内照常可调用，但追踪会打“已废弃”标记，并通知 {{ tool?.dependents?.length ?? 0 }} 个依赖方迁移。</p>
          <div class="field">
            <label>替代工具</label>
            <select v-model="replacedBy" class="inp">
              <option value="">请选择</option>
              <option v-for="item in replacements" :key="item" :value="item">{{ item }}</option>
            </select>
          </div>
          <div class="field">
            <label>截止日期</label>
            <input v-model="deadline" class="inp" type="date">
          </div>
        </div>
        <div class="mf">
          <button class="btn" type="button" @click="closeModal">取消</button>
          <button v-write class="btn pri" type="button" @click="confirmDeprecate">确认废弃</button>
        </div>
      </div>

      <div v-if="modal === 'version'" class="modal on" role="dialog" aria-label="发布新版本">
        <div class="mh">发布新版本 <span class="mono">{{ tool?.name }}</span></div>
        <div class="mb">
          <div class="radio">
            <label v-for="kind in VERSION_KINDS" :key="kind.id" :class="{ on: versionKind === kind.id }">
              <input v-model="versionKind" type="radio" name="vk" :value="kind.id">
              <span>
                {{ kind.title }}
                <small>{{ kind.id === 'break' ? breakHint(tool?.name || name) : kind.hint }}</small>
              </span>
            </label>
          </div>
          <ul v-if="checks.length" class="checks" style="margin-top: 12px">
            <li v-for="row in checks" :key="row.agent">
              <span class="ic" :class="row.state">{{ row.state === 'ok' ? '✓' : '·' }}</span>
              回归评测 {{ row.agent }}
              <small>{{ row.detail }}</small>
            </li>
          </ul>
        </div>
        <div class="mf">
          <button class="btn" type="button" :disabled="versionBusy" @click="closeModal">取消</button>
          <button v-write class="btn pri" type="button" :disabled="versionBusy" @click="submitVersion">发布</button>
        </div>
      </div>
    </template>
  </Teleport>
</template>
