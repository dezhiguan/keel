<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import { chatWithAgent, getAgent, type AgentDetail } from '@/api/agents'
import { toKeelError } from '@/api/http'
import { agentStatus, orDash } from '@/utils/format'

const route = useRoute()
const detail = ref<AgentDetail | null>(null)
const loading = ref(false)
const draft = ref('')
const reply = ref('')
const traceId = ref('')
const sending = ref(false)

async function send() {
  const text = draft.value.trim()
  if (!text || !detail.value?.name) return
  sending.value = true
  reply.value = ''
  traceId.value = ''
  try {
    const result = await chatWithAgent(detail.value.name, text)
    reply.value = result.text ?? ''
    traceId.value = result.traceId ?? ''
  } catch (error) {
    ElMessage.error(`发送失败：${toKeelError(error).message}`)
  } finally {
    sending.value = false
  }
}

async function load() {
  loading.value = true
  try {
    detail.value = await getAgent(String(route.params.name))
  } catch (error) {
    detail.value = null
    ElMessage.error(`加载智能体失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

watch(() => route.params.name, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>{{ detail?.displayName ?? '智能体详情' }}</h2>
      <span class="sub mono">{{ detail?.name }}</span>
      <StatusPill v-if="detail" v-bind="agentStatus(detail.status)" />
    </div>
    <section class="card">
      <h3>对话</h3>
      <p class="sub">发给已注册的智能体进程，回复来自模型调用。</p>
      <textarea v-model="draft" class="inp" rows="3" placeholder="输入一句话" style="width: 100%; box-sizing: border-box" />
      <div class="wfoot">
        <button class="btn pri" type="button" :disabled="sending || !draft.trim()" @click="send">{{ sending ? '发送中…' : '发送' }}</button>
        <RouterLink v-if="traceId" class="btn" :to="`/traces/${traceId}`">查看这条链路</RouterLink>
      </div>
      <p v-if="reply" style="white-space: pre-wrap">{{ reply }}</p>
    </section>
    <section class="card">
      <h3>登记</h3>
      <p>环境 {{ orDash(detail?.env) }} · 版本 {{ orDash(detail?.version) }} · 评分 {{ orDash(detail?.score) }} · 成本 {{ detail?.costCny == null ? '—' : `¥${detail.costCny.toFixed(2)}` }}</p>
      <p>模型 {{ detail?.models?.join('、') || '—' }}</p>
      <p>工具 {{ detail?.tools?.join('、') || '—' }}</p>
      <p>知识库 {{ detail?.knowledgeBases?.join('、') || '—' }}</p>
    </section>
    <section class="card">
      <h3>实例</h3>
      <el-empty v-if="!detail?.instanceList?.length" description="暂无实例" :image-size="60" />
      <table v-else class="t">
        <thead><tr><th>实例</th><th>来源</th><th>版本</th><th>就绪</th></tr></thead>
        <tbody>
          <tr v-for="row in detail.instanceList" :key="row.instanceId">
            <td class="mono">{{ row.instanceId }}</td>
            <td>{{ row.source }}</td>
            <td class="mono">{{ orDash(row.version) }}</td>
            <td>{{ row.ready ? '是' : '否' }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
