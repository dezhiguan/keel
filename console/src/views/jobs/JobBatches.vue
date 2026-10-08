<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createDevflowBatch, listDevflowBatches, previewDevflowBatch, type DevflowBatch, type DevflowBatchPreviewRow } from '@/api/devflow'
import { toKeelError } from '@/api/http'
import JobStatus from './JobStatus.vue'
import JobTabs from './JobTabs.vue'
import StageStrip from './StageStrip.vue'
import { MODES, flagView, passedGate } from './jobs'

const router = useRouter()
const batches = ref<DevflowBatch[]>([])
const loading = ref(false)
const draft = ref<DevflowBatchPreviewRow[] | null>(null)
const title = ref('客服中心 Q4 第一批')
const busy = ref(false)

async function load() {
  loading.value = true
  try {
    batches.value = (await listDevflowBatches()).items
  } catch (error) {
    ElMessage.error(`加载批次失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

async function upload() {
  try {
    draft.value = (await previewDevflowBatch()).rows
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

async function submit() {
  if (!draft.value) return
  busy.value = true
  try {
    const created = await createDevflowBatch(title.value, draft.value)
    draft.value = null
    ElMessage.success(`批次 ${created.batchId} 已创建：先试产 ${created.pilotJobId}`)
    await load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    busy.value = false
  }
}

function passed(batch: DevflowBatch) {
  return batch.jobs.filter((job) => passedGate(job)).length
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>研发任务</h2>
      <span class="tag-new">新页面</span>
      <span class="sub">一张需求表 = 一个批次，每行生产一个业务智能体。先试产 1 条，过门禁后放开整批</span>
      <span class="sp" />
      <button v-write class="btn pri" type="button" @click="upload">上传需求表</button>
    </div>
    <JobTabs current="batches" />

    <div v-if="draft" class="card">
      <h3>新批次预览 <small>spec-agent 已整批预检</small></h3>
      <div class="field">
        <label>批次名称</label>
        <input v-model="title" class="inp" style="max-width: 320px">
      </div>
      <table class="t">
        <thead>
          <tr><th>#</th><th>智能体 ID</th><th>标题</th><th>开发模式</th><th>负责人</th><th>预检</th></tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in draft" :key="row.targetAgent">
            <td class="mono">{{ index + 1 }}</td>
            <td class="mono">{{ row.targetAgent }}</td>
            <td>{{ row.title }}</td>
            <td>{{ MODES[row.mode].label }}</td>
            <td>{{ row.owner }}</td>
            <td>
              <span v-if="flagView(row.flag)" class="pill nd" :class="flagView(row.flag)?.cls">{{ flagView(row.flag)?.text }}</span>
              <span v-else class="pill p-ok">可生产</span>
            </td>
          </tr>
        </tbody>
      </table>
      <div style="display: flex; gap: 8px; justify-content: flex-end; margin-top: 12px">
        <button class="btn" type="button" @click="draft = null">放弃</button>
        <button v-write class="btn pri" type="button" :disabled="busy" @click="submit">创建批次（跳过标红行）</button>
      </div>
    </div>

    <div v-for="batch in batches" :key="batch.batchId" class="card">
      <h3>
        {{ batch.title }}
        <small class="mono">{{ batch.batchId }} · {{ batch.requester }} · {{ batch.createdAt }}</small>
        <span class="sp" />
        <span class="pill" :class="batch.pilotPassed ? 'p-ok' : 'p-warn'">{{ batch.pilotPassed ? '试产已过门禁，整批放开' : '试产中' }}</span>
      </h3>
      <div style="display: flex; gap: 16px; align-items: center; margin-bottom: 10px">
        <div class="bar" style="flex: 1"><i :style="{ width: `${batch.jobs.length ? (passed(batch) / batch.jobs.length) * 100 : 0}%`, background: 'var(--teal)' }" /></div>
        <span class="mono mut">{{ passed(batch) }}/{{ batch.jobs.length }} 已过门禁</span>
      </div>
      <table class="t">
        <tbody>
          <tr v-for="job in batch.jobs" :key="job.jobId" class="click" @click="router.push(`/jobs/${job.jobId}`)">
            <td class="nm">
              <b>{{ job.title }} <span v-if="batch.pilotJobId === job.jobId" class="pill nd p-soft">试产</span></b>
              <small class="mono">{{ job.jobId }} · {{ job.targetAgent }}</small>
            </td>
            <td>{{ MODES[job.mode].label }}</td>
            <td><JobStatus :job="job" /></td>
            <td style="width: 220px"><StageStrip :job="job" /></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
