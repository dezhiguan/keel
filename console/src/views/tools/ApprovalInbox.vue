<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { answerSuspendedRun, decideApproval, listApprovals, listSuspendedRuns, type Approval, type SuspendedRun } from '@/api/approvals'
import { listDevflowJobs, type DevflowJob } from '@/api/devflow'
import { toKeelError } from '@/api/http'
import { useApprovalsStore } from '@/stores/approvals'
import { useEnvStore } from '@/stores/env'
import { RISK, ago } from '@/utils/format'
import {
  GATE_LABEL,
  GROUPS,
  devflowItems,
  groupCount,
  pendingTotal,
  reviewPath,
  shownApprovals as approvalsIn,
  shownDevflow as devflowIn,
  shownRuns as runsIn,
  type Group,
} from './approvalGroups'

const SUBJECT: Record<NonNullable<Approval['subjectType']>, { label: string; cls: string }> = {
  'tool.call': { label: '工具调用', cls: 'p-warn' },
  'tool.config': { label: '工具变更', cls: 'p-warn' },
  'agent.config': { label: '智能体配置', cls: 'p-acc' },
  'agent.retire': { label: '智能体下线', cls: 'p-acc' },
  'data.export': { label: '数据导出', cls: 'p-bad' },
}
const RUN_REASON: Record<NonNullable<SuspendedRun['reason']>, { label: string; ok: string; no: string }> = {
  input_required: { label: '等用户回答', ok: '提交回复', no: '终止' },
  handoff: { label: '转人工', ok: '我来接管', no: '退回智能体' },
}

const approvalsStore = useApprovalsStore()
const envStore = useEnvStore()
const approvals = ref<Approval[]>([])
const runs = ref<SuspendedRun[]>([])
const jobs = ref<DevflowJob[]>([])
const group = ref<Group>('all')
const loading = ref(false)
const replies = reactive<Record<string, string>>({})

const devflow = computed(() => devflowItems(approvals.value, runs.value, jobs.value))
const count = (g: Group) => groupCount(g, approvals.value, runs.value, devflow.value)

const shownApprovals = computed(() => approvalsIn(group.value, approvals.value))
const shownRuns = computed(() => runsIn(group.value, runs.value))
const shownDevflow = computed(() => devflowIn(group.value, devflow.value))

async function loadJobs(): Promise<DevflowJob[]> {
  try {
    return (await listDevflowJobs()).items
  } catch {
    return []
  }
}

async function load() {
  loading.value = true
  try {
    const [a, r, j] = await Promise.all([
      listApprovals({ status: 'PENDING', size: 100, env: envStore.env }),
      listSuspendedRuns({ size: 100, env: envStore.env }),
      loadJobs(),
    ])
    approvals.value = a.items ?? []
    runs.value = r.items ?? []
    jobs.value = j
    approvalsStore.pending = pendingTotal(a.total ?? 0, r.total ?? 0, j)
  } catch (error) {
    ElMessage.error(`加载审批失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

async function decide(approval: Approval, decision: 'APPROVE' | 'REJECT') {
  try {
    await decideApproval(approval.id!, decision)
    ElMessage.success(decision === 'APPROVE' ? `已批准 ${approval.id}` : `已驳回 ${approval.id}`)
    load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

async function answer(run: SuspendedRun) {
  const text = replies[run.runId!]?.trim()
  if (!text) {
    ElMessage.warning('请先填写回复')
    return
  }
  try {
    await answerSuspendedRun(run.runId!, text)
    ElMessage.success(`已回复，${run.agent} 从挂起处继续执行`)
    load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

function terminate(run: SuspendedRun) {
  // TODO(P3-1): terminating a suspended run has no endpoint in console-api.openapi.yaml yet.
  ElMessage.info(`终止 ${run.runId} 尚未接入`)
}

watch(() => envStore.env, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>审批中心</h2>
      <span class="sub">所有需要人点一下的事都在这里。「研发任务」汇总研发任务的关口，不另开待办；工具、智能体、数据导出是审批单，「人工介入」是执行挂起等人回话，处理完智能体从断点继续</span>
    </div>
    <div class="chipsel" style="margin-bottom: 14px">
      <button v-for="[k, n] in GROUPS" :key="k" :class="{ on: group === k }" @click="group = k">{{ n }} · {{ count(k) }}</button>
    </div>

    <div style="max-width: 820px">
      <div v-for="d in shownDevflow" :key="d.key" class="appr">
        <div class="t1">
          <span class="pill p-acc">研发任务</span>
          <span class="pill nd p-soft">{{ GATE_LABEL[d.gate] }}</span>
          <RouterLink class="mono" :to="`/jobs/${d.jobId}`"><b>{{ d.jobId }}</b></RouterLink>
          <span v-if="d.title">{{ d.title }}</span>
          <span class="sp" />
          <small v-if="d.createdAt" class="mut">{{ ago(d.createdAt) }}</small>
        </div>
        <div class="act" style="margin-top: 10px">
          <RouterLink class="btn pri sm" :to="reviewPath(d.jobId)">处理</RouterLink>
          <span class="sp" />
          <small class="mut">目标智能体 <span class="mono">{{ d.targetAgent }}</span></small>
        </div>
      </div>

      <div v-for="a in shownApprovals" :key="a.id" class="appr">
        <div class="t1">
          <span class="pill" :class="SUBJECT[a.subjectType!].cls">{{ SUBJECT[a.subjectType!].label }}</span>
          <span class="pill nd" :class="RISK[a.risk!].cls">{{ RISK[a.risk!].label }}</span>
          <b class="mono">{{ a.subjectRef }}</b>
          <span class="sp" />
          <small class="mut">{{ ago(a.createdAt) }}</small>
        </div>
        <p>{{ a.summary }}</p>
        <div class="act">
          <button v-write class="btn pri sm" @click="decide(a, 'APPROVE')">批准</button>
          <button v-write class="btn sm" @click="decide(a, 'REJECT')">驳回</button>
          <span class="sp" />
          <small class="mut">
            {{ a.agent }} · {{ a.policyName }} ·
            <RouterLink v-if="a.runId && a.traceId" :to="`/traces/${a.traceId}`" style="color: var(--soft)">run {{ a.runId }} 挂起中 →</RouterLink>
            <template v-else>由人发起，无执行在等</template>
          </small>
        </div>
      </div>

      <div v-for="r in shownRuns" :key="r.runId" class="appr">
        <div class="t1">
          <span class="pill p-soft">{{ RUN_REASON[r.reason!].label }}</span>
          <b class="mono">{{ r.runId }}</b>
          <span class="sp" />
          <small class="mut">{{ ago(r.createdAt) }}</small>
        </div>
        <p>{{ r.prompt }}</p>
        <el-input v-model="replies[r.runId!]" placeholder="回复后智能体从挂起处继续执行" style="margin-bottom: 10px" />
        <div class="act">
          <button v-write class="btn pri sm" @click="answer(r)">{{ RUN_REASON[r.reason!].ok }}</button>
          <button v-write class="btn sm" @click="terminate(r)">{{ RUN_REASON[r.reason!].no }}</button>
          <span class="sp" />
          <small class="mut">
            {{ r.agent }} · 发起用户 {{ r.actorUser }} ·
            <RouterLink :to="`/traces/${r.traceId}`" style="color: var(--soft)">查看链路 →</RouterLink>
          </small>
        </div>
      </div>

      <div v-if="!shownDevflow.length && !shownApprovals.length && !shownRuns.length && !loading" class="card empty">这一类没有待办</div>
    </div>
  </div>
</template>
