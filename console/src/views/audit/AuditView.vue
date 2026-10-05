<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import { listAgents } from '@/api/agents'
import { listAuditEvents, requestAuditExport, verifyAuditChain, type AuditEvent, type AuditPage, type ListAuditQuery } from '@/api/audit'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { RISK, hms, type StatusTone } from '@/utils/format'
import { eventTime, payloadText, shortHash } from './auditDrawer'

const DECISION: Record<NonNullable<AuditEvent['decision']>, { label: string; tone: StatusTone }> = {
  allowed: { label: '允许', tone: 'ok' },
  approved: { label: '已批准', tone: 'ok' },
  denied: { label: '拒绝', tone: 'failed' },
  rejected: { label: '已驳回', tone: 'failed' },
  pending: { label: '待审批', tone: 'degraded' },
}

const envStore = useEnvStore()
const router = useRouter()
const agents = ref<string[]>([])
const result = ref<AuditPage | null>(null)
const loading = ref(false)
const verifying = ref(false)
const selected = ref<AuditEvent | null>(null)
const shown = ref(false)
const filter = reactive<{ agent?: string; risk?: ListAuditQuery['risk']; page: number; size: NonNullable<ListAuditQuery['size']> }>({
  agent: undefined,
  risk: undefined,
  page: 1,
  size: 10,
})

async function loadAgents() {
  try {
    const page = await listAgents({ env: envStore.env, page: 1, size: 100 })
    agents.value = (page.items ?? []).map((item) => item.name).filter((name): name is string => !!name)
  } catch {
    agents.value = []
  }
}

async function load() {
  loading.value = true
  try {
    result.value = await listAuditEvents({ env: envStore.env, agent: filter.agent, risk: filter.risk, page: filter.page, size: filter.size })
  } catch (error) {
    ElMessage.error(`加载审计事件失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

function search() {
  filter.page = 1
  load()
}

function setRisk(risk?: ListAuditQuery['risk']) {
  filter.risk = risk
  search()
}

async function verify() {
  verifying.value = true
  try {
    const r = await verifyAuditChain(filter.agent)
    if (r.intact) ElMessage.success(`哈希链完整：校验 ${r.checked} 条，用时 ${r.elapsedMs}ms`)
    else ElMessage.error(`哈希链断裂于 ${r.brokenAt}`)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    verifying.value = false
  }
}

async function exportAudit() {
  let reason: string
  try {
    ;({ value: reason } = await ElMessageBox.prompt('导出会先建审批单，批准后才生成脱敏文件。请填写用途：', '申请导出', {
      inputPlaceholder: '如：季度合规检查',
      inputValidator: (v) => !!v?.trim() || '必须填写用途',
    }))
  } catch {
    return
  }
  try {
    const r = await requestAuditExport({ env: envStore.env, agent: filter.agent, risk: filter.risk }, reason)
    ElMessage.success(`已提交审批 ${r.approvalId ?? ''}`.trim())
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

function close() {
  selected.value = null
}

function openTrace() {
  const traceId = selected.value?.traceId
  if (!traceId) return
  close()
  router.push(`/traces/${traceId}`)
}

function onKey(event: KeyboardEvent) {
  if (event.key === 'Escape' && selected.value) close()
}

watch(selected, async (event) => {
  shown.value = false
  if (!event) {
    document.body.style.overflow = ''
    return
  }
  document.body.style.overflow = 'hidden'
  await nextTick()
  requestAnimationFrame(() => {
    if (selected.value === event) shown.value = true
  })
})

watch(() => envStore.env, () => { loadAgents(); search() })
watch(() => [filter.page, filter.size], load, { immediate: true })
onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  document.body.style.overflow = ''
})
loadAgents()
</script>

<template>
  <div>
    <div class="vh">
      <h2>审计中心</h2>
      <span class="sub">只追加 · 哈希链防篡改 · 字段白名单 · 敏感信息脱敏</span>
      <span class="sp" />
      <button class="btn" :disabled="verifying" @click="verify">{{ verifying ? '校验中…' : '校验哈希链' }}</button>
      <button class="btn" @click="exportAudit">导出（需审批）</button>
    </div>
    <div class="toolbar">
      <select v-model="filter.agent" class="inp" @change="search">
        <option :value="undefined">全部智能体</option>
        <option v-for="a in agents" :key="a" :value="a">{{ a }}</option>
      </select>
      <div class="chipsel">
        <button :class="{ on: !filter.risk }" @click="setRisk(undefined)">全部风险</button>
        <button v-for="r in (['HIGH', 'MID', 'LOW'] as const)" :key="r" :class="{ on: filter.risk === r }" @click="setRisk(r)">{{ RISK[r].label }}</button>
      </div>
    </div>
    <div v-if="verifying" class="prog"><i style="width: 70%" /></div>

    <div v-loading="loading" class="card">
      <table class="t">
        <thead><tr><th>时间</th><th>智能体</th><th>操作人</th><th>动作</th><th>资源</th><th>风险</th><th>结果</th></tr></thead>
        <tbody>
          <tr v-for="e in result?.items ?? []" :key="e.eventId" class="click" @click="selected = e">
            <td class="mono">{{ hms(e.ts) }}</td>
            <td>{{ e.agent }}</td>
            <td>{{ e.actor?.userId }}</td>
            <td class="mono">{{ e.action }}</td>
            <td>{{ e.resource }}</td>
            <td><span class="pill nd" :class="RISK[e.risk!].cls">{{ RISK[e.risk!].label }}</span></td>
            <td><StatusPill v-bind="DECISION[e.decision!]" /></td>
          </tr>
          <tr v-if="!result?.items?.length && !loading"><td colspan="7" class="empty">没有符合条件的记录</td></tr>
        </tbody>
      </table>
      <Pager v-model:page="filter.page" v-model:size="filter.size" :total="result?.total ?? 0" />
    </div>

    <Teleport to="body">
      <template v-if="selected">
        <div class="mask" :class="{ on: shown }" @click="close" />
        <aside class="drawer" :class="{ on: shown }" role="dialog" aria-label="审计事件详情">
          <div class="dh">
            <h3 style="display: block">审计事件 <span class="mono" style="font-size: 13px; color: var(--mute); font-weight: 400">{{ selected.action }}</span></h3>
            <span class="sp" />
            <button class="x" type="button" aria-label="关闭" @click="close">×</button>
          </div>
          <div class="db">
            <div class="kv">
              <span>时间</span><b class="mono">{{ eventTime(selected.ts) }}</b>
              <span>智能体</span><b>{{ selected.agent }}</b>
              <span>操作人</span><b>{{ selected.actor?.userId }}</b>
              <span>资源</span><b>{{ selected.resource }}</b>
              <span>风险</span><b><span class="pill nd" :class="RISK[selected.risk!].cls">{{ RISK[selected.risk!].label }}</span></b>
              <span>trace</span><b class="mono">{{ selected.traceId || '—' }}</b>
            </div>
            <h4 style="margin: 16px 0 6px; color: var(--white); font-size: 13px">payload（仅白名单字段，已脱敏）</h4>
            <pre class="code">{{ payloadText(selected.payload) }}</pre>
            <h4 style="margin: 16px 0 6px; color: var(--white); font-size: 13px">哈希链</h4>
            <div class="kv">
              <span>hash</span><b class="mono">{{ shortHash(selected.hash) }}</b>
              <span>prev_hash</span><b class="mono">{{ shortHash(selected.prevHash) }}</b>
              <span>链</span>
              <b>{{ selected.agent }} · {{ selected.hashVerified ? '校验通过' : '校验未通过' }} <span class="pill" :class="selected.hashVerified ? 'p-ok' : 'p-bad'">{{ selected.hashVerified ? '✓' : '✗' }}</span></b>
            </div>
          </div>
          <div v-if="selected.traceId" class="df">
            <button class="btn" type="button" @click="openTrace">查看链路</button>
          </div>
        </aside>
      </template>
    </Teleport>
  </div>
</template>
