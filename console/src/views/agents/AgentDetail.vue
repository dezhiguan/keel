<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import { getAgent, type AgentDetail } from '@/api/agents'
import { toKeelError } from '@/api/http'
import { agentStatus, orDash } from '@/utils/format'

const route = useRoute()
const detail = ref<AgentDetail | null>(null)
const loading = ref(false)

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
