<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { answerSuspendedRun } from '@/api/approvals'
import { acceptDevflowSeedCases, uploadDevflowHumanSeeds, type DevflowJob, type DevflowReview, type DevflowSeedCase } from '@/api/devflow'
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
const humanJson = ref('[\n  {"caseId":"h-1","input":{"text":"输入示例"},"expected":{"decision":"预期决策"},"tags":["normal"]}\n]')

async function upload() {
  let parsed: unknown
  try { parsed = JSON.parse(humanJson.value) }
  catch { ElMessage.warning('种子用例必须是 JSON 数组'); return }
  if (!Array.isArray(parsed) || !parsed.length || parsed.some(item => !item || typeof item !== 'object' ||
    !String(item.caseId || '').startsWith('h-') || !item.input || !item.expected || !Array.isArray(item.tags))) {
    ElMessage.warning('每条用例都需要 h- 编号、input、expected 和 tags')
    return
  }
  busy.value = true
  try {
    await uploadDevflowHumanSeeds(props.job.jobId, parsed as DevflowSeedCase[])
    emit('done', '人工种子用例已提交，元智能体正在扩充评测集')
  } catch (error) { ElMessage.error(toKeelError(error).message) }
  finally { busy.value = false }
}

async function confirm() {
  if (!props.review.execution && !props.review.runId) {
    ElMessage.error('没有挂起的执行可以恢复')
    return
  }
  busy.value = true
  try {
    const result = await acceptDevflowSeedCases(props.job.jobId, accepted.value)
    if (!props.review.execution && props.review.runId) await answerSuspendedRun(props.review.runId, '确认')
    emit('done', `评测集已确认：采纳 ${result.acceptedAgentCount} 条，切出 ${result.holdoutCount} 条隐藏考题`)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <template v-if="review.seedInputRequired">
    <div class="banner info">S2 · 请上传至少 {{ review.minSeed || 1 }} 条人工编写的岗位考题。输入与预期均为 JSON 对象，编号以 h- 开头；提交后由 meta-agent 扩充，H2 再逐条确认。</div>
    <div class="card">
      <h3>人工种子用例</h3>
      <textarea v-model="humanJson" class="inp mono" rows="14" aria-label="人工种子用例 JSON" />
      <div style="display: flex; justify-content: flex-end; margin-top: 12px"><button v-write class="btn pri" type="button" :disabled="busy" @click="upload">提交给元智能体扩充</button></div>
    </div>
  </template>
  <template v-else>
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
</template>
