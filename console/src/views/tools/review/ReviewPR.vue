<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { takeoverDevflowJob, type DevflowJob, type DevflowReview } from '@/api/devflow'
import { toKeelError } from '@/api/http'

const props = defineProps<{ job: DevflowJob; review: DevflowReview }>()
const emit = defineEmits<{ done: [message: string] }>()

const busy = ref(false)

async function takeover() {
  busy.value = true
  try {
    await takeoverDevflowJob(props.job.jobId)
    emit('done', '已接管，在 PR 上直接提交')
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="card">
    <h3>
      <template v-if="review.pr">PR #{{ review.pr.number }} · </template>{{ job.targetAgent }}
      <small>approve 在 Git 托管里完成，这里只是入口</small>
    </h3>
    <div v-if="review.pr" class="kv">
      <span>改动</span><span class="mono" style="color: var(--text)">+{{ review.pr.additions }} −{{ review.pr.deletions }} · {{ review.pr.files }} 个文件</span>
      <span>评审</span><span style="color: var(--text)">{{ review.pr.reviewSummary }}</span>
      <span>沙箱</span><span class="okc">{{ review.pr.sandboxSummary }}</span>
    </div>
    <div v-else class="empty">PR 信息未产出</div>
    <div style="display: flex; gap: 8px; justify-content: flex-end; margin-top: 12px">
      <a v-if="review.pr" class="btn" :href="review.pr.url" target="_blank" rel="noopener noreferrer">打开 PR</a>
      <button v-write class="btn vio" type="button" :disabled="busy" @click="takeover">我来改</button>
    </div>
  </div>
</template>
