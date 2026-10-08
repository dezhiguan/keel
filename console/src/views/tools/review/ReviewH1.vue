<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { answerSuspendedRun } from '@/api/approvals'
import type { DevflowJob, DevflowReview } from '@/api/devflow'
import { toKeelError } from '@/api/http'
import { MODES } from '@/views/jobs/jobs'
import { canSendBack } from '../approvalGroups'

const props = defineProps<{ job: DevflowJob; review: DevflowReview }>()
const emit = defineEmits<{ done: [message: string] }>()

type Mode = keyof typeof MODES
const RISK_CLASS = { HIGH: 'p-bad', MID: 'p-warn', LOW: 'p-ok' } as const

const locked = props.job.layer !== 'BIZ'
const sheet = reactive({
  users: props.review.specSheet?.users ?? '',
  io: props.review.specSheet?.io ?? '',
  success: props.review.specSheet?.success ?? '',
})
const mode = ref<Mode>(locked ? 'COLLAB' : props.review.suggestedMode ?? props.job.mode)
const note = ref('')
const noteError = ref(false)
const busy = ref(false)

// TODO(DF-5 / DF-2): edits to the spec sheet and the chosen mode have no field to travel in yet; /runs/{runId}/input only carries text.
async function reply(text: string, message: string) {
  if (!props.review.runId) {
    ElMessage.error('没有挂起的执行可以恢复')
    return
  }
  busy.value = true
  try {
    await answerSuspendedRun(props.review.runId, text)
    emit('done', message)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    busy.value = false
  }
}

function sendBack() {
  if (!canSendBack(note.value)) {
    noteError.value = true
    ElMessage.warning('退回时请写修改意见')
    return
  }
  noteError.value = false
  reply(note.value.trim(), '已退回，spec-agent 将出新版需求单')
}
</script>

<template>
  <div class="row2">
    <div class="card">
      <h3>需求单 <small>spec-agent 产出，可直接改</small></h3>
      <div class="kv" style="margin-bottom: 4px">
        <span>标题</span><b>{{ review.specSheet?.title ?? job.title }}</b>
        <span>目标</span><span style="color: var(--text)">{{ review.specSheet?.goal ?? job.goal }}</span>
      </div>
      <div class="field"><label>目标用户与场景</label><textarea v-model="sheet.users" class="inp" /></div>
      <div class="field"><label>输入 / 输出</label><textarea v-model="sheet.io" class="inp" rows="3" /></div>
      <div class="field"><label>成功标准</label><textarea v-model="sheet.success" class="inp" /></div>
      <div v-if="review.specSheet?.pending.length" class="field">
        <label>待确认</label>
        <ul class="plain">
          <li v-for="item in review.specSheet.pending" :key="item">{{ item }}</li>
        </ul>
      </div>
      <div class="field">
        <label>开发模式</label>
        <div class="chipsel">
          <button
            v-for="(item, key) in MODES"
            :key="key"
            type="button"
            :class="{ on: mode === key }"
            :disabled="locked && key !== 'COLLAB'"
            @click="mode = key"
          >{{ item.label }}</button>
        </div>
        <div class="hint">
          <template v-if="locked">研发类任务锁定人机协作。</template>
          <template v-else-if="review.suggestedMode">spec-agent 建议：{{ MODES[review.suggestedMode].label }}。可以改。</template>
        </div>
      </div>
      <div class="field">
        <label>修改意见（退回时必填）<b v-if="noteError">*</b></label>
        <textarea v-model="note" class="inp" :class="{ err: noteError }" @input="noteError = false" />
      </div>
    </div>
    <div>
      <div class="card">
        <h3>授权清单 <small>H3 会发给所有者，走工具页现有授权流程</small></h3>
        <table class="t">
          <tbody>
            <tr v-for="row in review.authList ?? []" :key="row.resource">
              <td class="mono">{{ row.resource }}</td>
              <td><span class="pill nd" :class="RISK_CLASS[row.risk]">{{ row.risk.toLowerCase() }}</span></td>
              <td>{{ row.owner }}</td>
            </tr>
            <tr v-if="!review.authList?.length"><td class="empty">无需授权</td></tr>
          </tbody>
        </table>
      </div>
      <div class="card">
        <h3>agent.yaml 草稿</h3>
        <pre class="code">{{ review.manifestYaml ?? '—' }}</pre>
      </div>
    </div>
  </div>
  <div class="card" style="display: flex; gap: 8px; justify-content: flex-end; align-items: center">
    <span class="mut" style="margin-right: auto">确认后恢复执行（重新换票）；退回会让 spec-agent 出新版。</span>
    <button v-write class="btn" type="button" :disabled="busy" @click="sendBack">退回修改</button>
    <button v-write class="btn pri" type="button" :disabled="busy" @click="reply('确认', '需求单已确认，研发任务继续')">确认需求单</button>
  </div>
</template>
