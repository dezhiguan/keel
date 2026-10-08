<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getDevflowJob, getDevflowReview, type DevflowJob, type DevflowReview } from '@/api/devflow'
import { toKeelError } from '@/api/http'
import { layerClass, layerShort } from '@/views/jobs/jobs'
import { GATE_LABEL } from '../approvalGroups'
import ReviewH1 from './ReviewH1.vue'
import ReviewH2 from './ReviewH2.vue'
import ReviewH4 from './ReviewH4.vue'
import ReviewPR from './ReviewPR.vue'

const route = useRoute()
const router = useRouter()
const jobId = computed(() => String(route.params.jobId ?? ''))
const job = ref<DevflowJob | null>(null)
const review = ref<DevflowReview | null>(null)
const loading = ref(false)
const state = ref<'ok' | 'missing' | 'closed'>('ok')

const title = computed(() => {
  if (!review.value) return job.value?.title ?? jobId.value
  return review.value.gate === 'PR' ? GATE_LABEL.PR : `${review.value.gate} · ${GATE_LABEL[review.value.gate]}`
})

async function load() {
  loading.value = true
  review.value = null
  try {
    job.value = await getDevflowJob(jobId.value)
    review.value = await getDevflowReview(jobId.value)
    state.value = 'ok'
  } catch (error) {
    const keel = toKeelError(error)
    if (keel.code === 'SERVER_NOT_FOUND') state.value = 'missing'
    else if (keel.code === 'RUN_NOT_RESUMABLE') state.value = 'closed'
    else ElMessage.error(`加载关口失败：${keel.message}`)
  } finally {
    loading.value = false
  }
}

function done(message: string) {
  ElMessage.success(message)
  router.push(`/jobs/${encodeURIComponent(jobId.value)}`)
}

watch(jobId, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>{{ state === 'ok' ? title : job?.title ?? jobId }}</h2>
      <span v-if="job && review" class="sub">
        <span class="ly" :class="layerClass(job.layer)">{{ layerShort(job.layer) }}</span>
        {{ job.title }} · <span class="mono">{{ job.jobId }}</span>
      </span>
      <span class="sp" />
      <RouterLink class="btn ghost" to="/approvals">← 审批中心</RouterLink>
      <RouterLink v-if="state !== 'missing'" class="btn ghost" :to="`/jobs/${encodeURIComponent(jobId)}`">任务详情</RouterLink>
    </div>

    <div v-if="state === 'missing'" class="empty">任务不存在</div>
    <div v-else-if="state === 'closed'" class="card empty">该任务当前不在人工关口</div>
    <template v-else-if="job && review">
      <ReviewH1 v-if="review.gate === 'H1'" :job="job" :review="review" @done="done" />
      <ReviewH2 v-else-if="review.gate === 'H2'" :job="job" :review="review" @done="done" />
      <ReviewH4 v-else-if="review.gate === 'H4'" :job="job" :review="review" @done="done" />
      <ReviewPR v-else :job="job" :review="review" @done="done" />
    </template>
  </div>
</template>
