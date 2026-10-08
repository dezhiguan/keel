<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { answerSuspendedRun } from '@/api/approvals'
import { acceptDevflowSeedCases, type DevflowJob, type DevflowReview } from '@/api/devflow'
import { toKeelError } from '@/api/http'
import { acceptedIds, defaultSelection, holdoutCount } from '../approvalGroups'

const props = defineProps<{ job: DevflowJob; review: DevflowReview }>()
const emit = defineEmits<{ done: [message: string] }>()

const cases = computed(() => props.review.cases ?? [])
const selection = reactive<Record<string, boolean>>(defaultSelection(cases.value))
const accepted = computed(() => acceptedIds(selection))
const human = computed(() => props.review.humanCount ?? 0)
const holdout = computed(() => holdoutCount(human.value, props.review.holdoutRatio ?? 0))
const busy = ref(false)

async function confirm() {
  if (!props.review.runId) {
    ElMessage.error('没有挂起的执行可以恢复')
    return
  }
  busy.value = true
  try {
    const result = await acceptDevflowSeedCases(props.job.jobId, accepted.value)
    await answerSuspendedRun(props.review.runId, '确认')
    emit('done', `评测集已确认：采纳 ${result.acceptedAgentCount} 条，切出 ${result.holdoutCount} 条隐藏考题`)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="banner info">
    <span>人给用例 <b class="mono">{{ human }}</b> 条。确认后服务端随机切出 <b class="mono">{{ holdout }}</b> 条隐藏考题。下面是 eval-agent 扩充的用例，勾选的才计分。</span>
  </div>
  <div class="card">
    <h3>扩充用例 <small>origin:agent · 已采纳 {{ accepted.length }}/{{ cases.length }}</small></h3>
    <table class="t">
      <tbody>
        <tr v-for="item in cases" :key="item.caseId">
          <td><input v-model="selection[item.caseId]" v-write type="checkbox" style="accent-color: var(--acc)" :aria-label="`采纳 ${item.caseId}`" /></td>
          <td><span class="chip">{{ item.tag }}</span></td>
          <td class="mono" style="font-size: 11.8px">{{ item.input }}</td>
          <td>{{ item.expected }}</td>
          <td class="mut">{{ item.reason }}</td>
        </tr>
        <tr v-if="!cases.length"><td class="empty">eval-agent 没有扩充用例</td></tr>
      </tbody>
    </table>
  </div>
  <div class="card" style="display: flex; gap: 8px; justify-content: flex-end; align-items: center">
    <span class="mut" style="margin-right: auto">隐藏考题确认后不可再查看内容，这里和接口都只给条数。</span>
    <button v-write class="btn pri" type="button" :disabled="busy" @click="confirm">确认评测集（采纳 {{ accepted.length }} 条）</button>
  </div>
</template>
