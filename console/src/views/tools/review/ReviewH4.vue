<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { decideApproval } from '@/api/approvals'
import type { DevflowJob, DevflowReview } from '@/api/devflow'
import { toKeelError } from '@/api/http'
import { cny } from '@/views/jobs/jobs'
import { ringColor } from '../approvalGroups'
import ScoreRing from './ScoreRing.vue'

const props = defineProps<{ job: DevflowJob; review: DevflowReview }>()
const emit = defineEmits<{ done: [message: string] }>()

const busy = ref(false)

async function decide(decision: 'APPROVE' | 'REJECT') {
  if (!props.review.approvalId) {
    ElMessage.error('没有待处理的发布审批单')
    return
  }
  busy.value = true
  try {
    await decideApproval(props.review.approvalId, decision)
    emit('done', decision === 'APPROVE' ? '已批准发布，等待合并和 CI 发布' : '已驳回，回到开发并计入修复轮次')
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="banner warn">
    <span>release-agent 申请调用 <span class="mono">git.pr.merge</span>（risk: high），审批单类型 tool.call。批准后合并 PR，由仓库 CI 跑 keel gate 和 keel release。</span>
  </div>
  <div class="row2">
    <div class="card">
      <h3>门禁报告</h3>
      <template v-if="review.gateReport">
        <div style="display: flex; gap: 30px; margin-bottom: 12px">
          <ScoreRing :score="review.gateReport.visible" :min-score="review.gateReport.minScore" label="可见用例" :sub="`≥ ${review.gateReport.minScore.toFixed(2)}`" />
          <ScoreRing :score="review.gateReport.holdout" :min-score="review.gateReport.minScore" label="隐藏考题" :sub="`≥ ${review.gateReport.minScore.toFixed(2)} · 独立计分`" />
        </div>
        <table v-if="review.gateReport.byTag.length" class="t">
          <thead><tr><th>维度</th><th>可见用例</th><th>隐藏考题</th></tr></thead>
          <tbody>
            <tr v-for="row in review.gateReport.byTag" :key="row.tag">
              <td>{{ row.tag }}</td>
              <td class="mono" :style="{ color: ringColor(row.visible, review.gateReport.minScore) }">{{ row.visible.toFixed(2) }}</td>
              <td class="mono" :style="{ color: ringColor(row.holdout, review.gateReport.minScore) }">{{ row.holdout.toFixed(2) }}</td>
            </tr>
          </tbody>
        </table>
      </template>
      <div v-else class="empty">门禁报告未产出</div>
    </div>
    <div class="card">
      <h3>上线后它将拥有</h3>
      <div v-if="review.ownership" class="kv">
        <span>智能体</span><span class="mono" style="color: var(--text)">{{ review.ownership.agent }} {{ review.ownership.version }}</span>
        <span>工具</span>
        <span>
          <template v-if="review.ownership.tools.length"><span v-for="tool in review.ownership.tools" :key="tool" class="chip">{{ tool }}</span></template>
          <template v-else>—</template>
        </span>
        <span>日预算</span><span class="mono" style="color: var(--text)">{{ review.ownership.agent }}-prod · {{ cny(review.ownership.dailyBudgetCny, 0) }}</span>
        <span>代码来源</span><span style="color: var(--text)">智能体 {{ review.ownership.agentCommits }} 次提交 · 人工 {{ review.ownership.humanCommits }} 次</span>
      </div>
      <div v-else class="empty">—</div>
    </div>
  </div>
  <div class="card" style="display: flex; gap: 8px; justify-content: flex-end; align-items: center">
    <span class="mut" style="margin-right: auto">驳回会回到开发，计入修复轮次。</span>
    <button v-write class="btn danger" type="button" :disabled="busy" @click="decide('REJECT')">驳回</button>
    <button v-write class="btn pri" type="button" :disabled="busy" @click="decide('APPROVE')">批准发布</button>
  </div>
</template>
