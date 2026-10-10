<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import { listPrompts, type PromptSummary } from '@/api/prompts'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { formatWhen, matchesStatus, STATUS_FILTERS, visibleEnvs, type EnvName } from './promptView'

const router = useRouter()
const envStore = useEnvStore()
const rows = ref<PromptSummary[]>([])
const langfuseUrl = ref('')
const agent = ref('')
const status = ref('all')
const page = ref(1)
const size = ref<10 | 20 | 50 | 100>(10)
const loading = ref(false)
const error = ref('')

const agents = computed(() => [...new Set(rows.value.map((row) => row.agent))])
const envs = computed(() => visibleEnvs(envStore.env))
const filtered = computed(() => rows.value.filter((row) => (!agent.value || row.agent === agent.value) && matchesStatus(row.states, status.value)))
const paged = computed(() => filtered.value.slice((page.value - 1) * size.value, page.value * size.value))

function versionOf(row: PromptSummary, env: EnvName) {
  return row.envs?.[env]?.version
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listPrompts('all')
    rows.value = data.items ?? []
    langfuseUrl.value = data.langfuseUrl ?? ''
  } catch (caught) {
    rows.value = []
    error.value = toKeelError(caught).message
    ElMessage.error(`加载提示词失败：${error.value}`)
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => envStore.env, () => {
  page.value = 1
})

function pickAgent(event: Event) {
  agent.value = (event.target as HTMLSelectElement).value
  page.value = 1
}

function pickStatus(value: string) {
  status.value = value
  page.value = 1
}
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>提示词</h2>
      <span class="sub">{{ envStore.env === 'all' ? '全部环境' : envStore.env }} · 四个环境共用一个 Langfuse 项目，每个环境一个标签；staging 生效即回归，prod 只经 <code>keel release</code></span>
      <span class="sp" />
      <a v-if="langfuseUrl" class="btn" :href="langfuseUrl" target="_blank" rel="noopener">在 Langfuse 中打开 ↗</a>
    </div>
    <div class="toolbar">
      <select class="inp" :value="agent" @change="pickAgent">
        <option value="">全部智能体</option>
        <option v-for="name in agents" :key="name" :value="name">{{ name }}</option>
      </select>
      <div class="chipsel">
        <button v-for="[value, label] in STATUS_FILTERS" :key="value" type="button" :class="{ on: status === value }" @click="pickStatus(value)">{{ label }}</button>
      </div>
    </div>
    <div class="card">
      <h3>提示词<small>一行一个提示词，Langfuse 里的名字是 智能体/名称；表格里是各环境标签所在的版本</small></h3>
      <table class="t">
        <thead>
          <tr>
            <th>提示词</th>
            <th>类型</th>
            <th v-for="env in envs" :key="env">{{ env }}{{ env === 'prod' ? '（production）' : '' }}</th>
            <th>最近修改</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!paged.length"><td :colspan="envs.length + 4" class="empty">{{ error || '没有符合条件的提示词' }}</td></tr>
          <tr v-for="row in paged" :key="`${row.agent}/${row.name}`" class="click" @click="router.push(`/prompts/${row.agent}/${row.name}`)">
            <td class="nm"><b class="mono">{{ row.agent }}/{{ row.name }}</b><small>{{ row.summary || (row.declared === false ? '已不在 manifest 中' : '还没有版本') }}</small></td>
            <td><span class="pill nd p-soft">{{ row.type }}</span></td>
            <td v-for="env in envs" :key="env">
              <template v-if="versionOf(row, env)">
                <span class="mono">v{{ versionOf(row, env) }}</span>
                <span v-if="env === 'staging' && row.gate === 'passed'" class="pill p-ok">回归通过</span>
                <span v-else-if="env === 'staging' && row.gate === 'pending'" class="pill p-warn">回归中</span>
                <span v-else-if="env === 'staging' && row.gate === 'failed'" class="pill p-bad">回归未过</span>
                <span v-else-if="env === 'staging' && row.gate === 'invalid'" class="pill p-bad">回归作废</span>
                <span v-else-if="env === 'staging' && row.gate === 'none'" class="pill p-mute">未回归</span>
                <span v-if="row.drift?.includes(env)" class="pill p-bad">漂移</span>
              </template>
              <span v-else class="mut">—</span>
            </td>
            <td>{{ row.updatedBy || '—' }} · <span class="mono mut">{{ formatWhen(row.updatedAt) }}</span></td>
            <td>
              <span v-for="state in row.states" :key="state.id" class="pill" :class="state.tone" style="margin: 1px 2px 1px 0">{{ state.label }}</span>
            </td>
          </tr>
        </tbody>
      </table>
      <Pager v-model:page="page" v-model:size="size" :total="filtered.length" />
      <div class="srcnote">只列 manifest <code>prompts.items</code> 里声明过的提示词。Dify 应用的提示词不在这里管。版本号全局一套：在某环境生效只是把该环境的标签挪到某一版。"代码里有新版本"是 <code>keel register</code> / CI 从 <code>prompts/</code> 文件带上来、还没在任何环境生效的版本。</div>
    </div>
  </div>
</template>
