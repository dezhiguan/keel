<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import JsonViewer from '@/components/JsonViewer.vue'
import Pager from '@/components/Pager.vue'
import StatusPill from '@/components/StatusPill.vue'
import { listAuditEvents, requestAuditExport, verifyAuditChain, type AuditEvent, type AuditPage, type ListAuditQuery } from '@/api/audit'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { RISK, hms, type StatusTone } from '@/utils/format'

const AGENTS = ['careermate', 'askdb', 'offshore-wind', 'cs-bot', 'ops-copilot', 'prd-agent', 'code-review', 'test-gen', 'dev-copilot']
const DECISION: Record<NonNullable<AuditEvent['decision']>, { label: string; tone: StatusTone }> = {
  allowed: { label: '允许', tone: 'ok' },
  approved: { label: '已批准', tone: 'ok' },
  denied: { label: '拒绝', tone: 'failed' },
  rejected: { label: '已驳回', tone: 'failed' },
  pending: { label: '待审批', tone: 'degraded' },
}

const envStore = useEnvStore()
const result = ref<AuditPage | null>(null)
const loading = ref(false)
const verifying = ref(false)
const selected = ref<AuditEvent | null>(null)
const filter = reactive<{ agent?: string; risk?: ListAuditQuery['risk']; page: number; size: NonNullable<ListAuditQuery['size']> }>({
  agent: undefined,
  risk: undefined,
  page: 1,
  size: 10,
})

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
    const r = await requestAuditExport({ agent: filter.agent, risk: filter.risk }, reason)
    ElMessage.success(`已提交审批 ${r.approvalId ?? ''}`.trim())
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

watch(() => envStore.env, search)
watch(() => [filter.page, filter.size], load, { immediate: true })
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
        <option v-for="a in AGENTS" :key="a" :value="a">{{ a }}</option>
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

    <el-drawer :model-value="!!selected" :title="selected?.eventId" size="520px" @close="selected = null">
      <template v-if="selected">
        <div class="kv">
          <span>时间</span><b class="mono">{{ selected.ts }}</b>
          <span>智能体 / 环境</span><b>{{ selected.agent }} · {{ selected.env }}</b>
          <span>操作人</span><b>{{ selected.actor?.userId }}</b>
          <span>动作</span><b class="mono">{{ selected.action }}</b>
          <span>资源</span><b>{{ selected.resource }}</b>
          <span>风险</span><b><span class="pill nd" :class="RISK[selected.risk!].cls">{{ RISK[selected.risk!].label }}</span></b>
          <span>结果</span><b><StatusPill v-bind="DECISION[selected.decision!]" /></b>
          <span>审批人</span><b>{{ selected.approver ?? '—' }}</b>
          <span>trace</span>
          <b>
            <RouterLink v-if="selected.traceId" :to="`/traces/${selected.traceId}`" class="mono" style="color: var(--soft)">{{ selected.traceId }}</RouterLink>
            <template v-else>—</template>
          </b>
          <span>输入摘要</span><b class="mono">{{ selected.inputDigest }}</b>
          <span>哈希校验</span><b>{{ selected.hashVerified ? '通过' : '未通过' }}</b>
        </div>
        <h4 style="margin: 16px 0 8px; color: var(--white); font-size: 13px">payload<small class="mut">（只含 captureFields 白名单字段，已脱敏）</small></h4>
        <JsonViewer :value="selected.payload" />
      </template>
    </el-drawer>
  </div>
</template>
