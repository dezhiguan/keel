<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listDevflowJobs, type DevflowJob, type DevflowJobList } from '@/api/devflow'
import { toKeelError } from '@/api/http'
import JobStatus from './JobStatus.vue'
import JobTabs from './JobTabs.vue'
import StageStrip from './StageStrip.vue'
import { BOARD_POLL_MS, COLUMNS, MODES, cardTone, cny, isActive, isFinished, layerClass, layerShort } from './jobs'

const router = useRouter()
const board = ref<DevflowJobList | null>(null)
const loading = ref(false)

const summary = computed(() => board.value?.summary)
const active = computed(() => (board.value?.items ?? []).filter((job) => isActive(job.status)))
const finished = computed(() => (board.value?.items ?? []).filter((job) => isFinished(job.status)))

function columnJobs(stages: string[]) {
  return active.value.filter((job) => stages.includes(job.stage))
}

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    board.value = await listDevflowJobs()
  } catch (error) {
    if (!silent) ElMessage.error(`加载研发任务失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

function onVisible() {
  if (document.visibilityState === 'visible') load(true)
}

let timer = 0
onMounted(() => {
  load()
  document.addEventListener('visibilitychange', onVisible)
  timer = window.setInterval(() => {
    if (document.visibilityState !== 'hidden') load(true)
  }, BOARD_POLL_MS)
})
onUnmounted(() => {
  window.clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisible)
})

function open(job: DevflowJob) {
  router.push(`/jobs/${job.jobId}`)
}
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>研发任务</h2>
      <span class="tag-new">新页面</span>
      <span class="sub">由智能体生产或改造智能体的全过程。等人处理的事项在审批中心</span>
      <span class="sp" />
      <button v-write class="btn pri" type="button" @click="router.push('/agents/new')">＋ 新建智能体</button>
    </div>
    <JobTabs current="board" />

    <div class="kpis">
      <div class="kpi">
        <div class="l">进行中</div>
        <div class="v">{{ summary?.active ?? '—' }}</div>
        <div class="d">排队 {{ summary?.queued ?? 0 }} · 今日上限 {{ summary?.dailyLimit ?? '—' }}</div>
      </div>
      <div class="kpi">
        <div class="l">等人处理</div>
        <div class="v warnc">{{ summary?.waitingHuman ?? '—' }}</div>
        <div class="d"><RouterLink to="/approvals">去审批中心</RouterLink></div>
      </div>
      <div class="kpi">
        <div class="l">人工开发中</div>
        <div class="v vioc">{{ summary?.humanDev ?? '—' }}</div>
        <div class="d">接管或骨架模式</div>
      </div>
      <div class="kpi">
        <div class="l">首次门禁通过率</div>
        <div class="v">{{ summary ? `${Math.round(summary.firstGatePassRate * 100)}%` : '—' }}</div>
        <div class="d">3 轮内 {{ summary ? `${Math.round(summary.withinRoundsPassRate * 100)}%` : '—' }}</div>
      </div>
      <div class="kpi">
        <div class="l">本月花费</div>
        <div class="v">{{ summary ? cny(summary.spentCny, 0) : '—' }}</div>
        <div class="d"><RouterLink to="/models">明细见模型网关</RouterLink></div>
      </div>
    </div>

    <div class="kan">
      <div v-for="column in COLUMNS" :key="column.name" class="col">
        <div class="ch">{{ column.name }}<small>{{ column.hint }}</small><span class="n">{{ columnJobs(column.stages).length }}</span></div>
        <template v-if="columnJobs(column.stages).length">
          <RouterLink
            v-for="job in columnJobs(column.stages)"
            :key="job.jobId"
            class="jc"
            :class="cardTone(job.status)"
            :to="`/jobs/${job.jobId}`"
          >
            <div class="t1">
              <span class="ly" :class="layerClass(job.layer)">{{ layerShort(job.layer) }}</span>
              <b>{{ job.title }}</b>
              <small>{{ job.jobId }}</small>
            </div>
            <div class="t2">
              <span class="mono">{{ job.targetAgent }}</span>
              <span>{{ MODES[job.mode].label }}</span>
              <span>{{ cny(job.spentCny) }}/{{ job.budgetCny }}</span>
              <span v-if="job.fixRounds" class="warnc">修复 {{ job.fixRounds }}/{{ job.maxFixRounds }}</span>
              <span v-if="job.batchId">批次 {{ job.batchId }}</span>
            </div>
            <div style="margin-top: 6px"><JobStatus :job="job" /></div>
            <StageStrip :job="job" />
          </RouterLink>
        </template>
        <div v-else class="empty">空</div>
      </div>
    </div>

    <div class="card">
      <h3>最近结束</h3>
      <table class="t">
        <thead>
          <tr><th>任务</th><th>分类</th><th>产出智能体</th><th>结果</th><th>花费</th></tr>
        </thead>
        <tbody>
          <tr v-for="job in finished" :key="job.jobId" class="click" @click="open(job)">
            <td class="nm"><b>{{ job.title }}</b><small class="mono">{{ job.jobId }}</small></td>
            <td><span class="ly" :class="layerClass(job.layer)">{{ layerShort(job.layer) }}</span></td>
            <td class="mono">{{ job.targetAgent }}</td>
            <td><JobStatus :job="job" /></td>
            <td class="mono">{{ cny(job.spentCny) }}</td>
          </tr>
          <tr v-if="!finished.length"><td colspan="5" class="empty">没有已结束的任务</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
