<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import ToolStatusPill from './ToolStatusPill.vue'
import { deprecateTool, getTool, publishToolVersion, retireTool, type ToolDetail } from '@/api/tools'
import { toKeelError } from '@/api/http'
import { RISK, agentStatus } from '@/utils/format'

const ACCESS = { READ: '读', WRITE: '写', EXEC: '执行' } as const
const SCOPE = { PRIVATE: '私有', SHARED: '共享' } as const

const route = useRoute()
const router = useRouter()
const tool = ref<ToolDetail | null>(null)
const loading = ref(false)
const deprecating = ref(false)
const publishing = ref(false)
const form = reactive({ replacedBy: '', deadline: '' })
const versionForm = reactive({ version: '', description: '', breaking: false })

async function load() {
  loading.value = true
  try {
    tool.value = await getTool(String(route.params.name))
  } catch (error) {
    ElMessage.error(`加载工具失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

async function submitDeprecate() {
  if (!form.replacedBy || !form.deadline) {
    ElMessage.warning('废弃必须给出替代工具和截止日期')
    return
  }
  try {
    await deprecateTool(tool.value!.name!, form.replacedBy, form.deadline)
    deprecating.value = false
    ElMessage.success('已废弃，依赖方会收到迁移通知')
    load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

async function retire() {
  try {
    await ElMessageBox.confirm(`确认下线 ${tool.value!.name}？下线后所有调用都会返回 TOOL_RETIRED。`, '下线工具', { type: 'warning' })
  } catch {
    return
  }
  try {
    await retireTool(tool.value!.name!)
    ElMessage.success('已下线')
    load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

function openPublish() {
  versionForm.version = ''
  versionForm.description = tool.value?.description ?? ''
  versionForm.breaking = false
  publishing.value = true
}

async function submitPublish() {
  if (!versionForm.version.trim()) {
    ElMessage.warning('版本不能为空')
    return
  }
  try {
    const result = await publishToolVersion(tool.value!.name!, {
      version: versionForm.version.trim(),
      description: versionForm.description.trim(),
      breaking: versionForm.breaking,
    })
    publishing.value = false
    const triggered = result.triggeredRegressions ?? []
    ElMessage.success(triggered.length ? `已发布，将回归：${triggered.join('、')}` : '已发布')
    load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

watch(() => route.params.name, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <button class="btn sm" @click="router.push('/tools')">← 工具</button>
      <h2 class="mono">{{ route.params.name }}</h2>
      <ToolStatusPill v-if="tool" :status="tool.status" />
      <span class="sp" />
      <template v-if="tool && tool.status !== 'RETIRED'">
        <button class="btn danger" @click="retire">下线</button>
        <button v-if="tool.status !== 'DEPRECATED'" class="btn" @click="deprecating = true">废弃</button>
        <button class="btn pri" @click="openPublish">发布新版本</button>
      </template>
    </div>

    <template v-if="tool">
      <div class="card">
        <div class="kv">
          <span>说明</span><b>{{ tool.description }}</b>
          <span>范围 / 所有者</span><b>{{ SCOPE[tool.scope!] }} · {{ tool.ownerAgent ?? tool.ownerOrg }}</b>
          <span>读写 / 风险</span><b>{{ ACCESS[tool.access!] }} · <span class="pill nd" :class="RISK[tool.risk!].cls">{{ RISK[tool.risk!].label }}</span></b>
          <span>审批策略</span><b>{{ tool.approvalPolicy ?? '—' }}</b>
          <span>当前版本</span><b class="mono">{{ tool.version }}</b>
          <span>MCP 地址</span><b class="mono">{{ tool.provider ?? '—' }}</b>
          <template v-if="tool.status === 'DEPRECATED'">
            <span>替代工具</span><b class="mono">{{ tool.replacedBy }}（截止 {{ tool.deprecateDeadline }}）</b>
          </template>
        </div>
      </div>

      <div class="card">
        <h3>依赖方<small>manifest 声明 + 30 天调用</small></h3>
        <table v-if="tool.dependents?.length" class="t">
          <thead><tr><th>智能体</th><th>环境</th><th>状态</th><th>manifest 声明</th></tr></thead>
          <tbody>
            <tr v-for="d in tool.dependents" :key="d.agent">
              <td>{{ d.agent }}</td>
              <td>{{ d.env }}</td>
              <td><StatusPill v-bind="agentStatus(d.agentStatus)" /></td>
              <td>{{ d.declaredInManifest ? '是' : '否' }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="mut">没有智能体在用</p>
      </div>

      <div class="card">
        <h3>版本历史</h3>
        <table class="t">
          <thead><tr><th>版本</th><th>变更</th><th>破坏兼容</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="v in tool.versions" :key="v.version">
              <td class="mono">{{ v.version }}</td>
              <td>{{ v.change }}</td>
              <td>{{ v.breaking ? '是' : '否' }}</td>
              <td class="mono">{{ v.at?.slice(5, 10) }}</td>
            </tr>
          </tbody>
        </table>
        <p class="mut" style="font-size: 12px; margin-top: 12px">
          生命周期：已注册 → 上线 → 废弃 → 下线。改描述或参数会触发所有依赖方回归评测；破坏兼容必须发新名字；有 prod 依赖不能直接下线。
        </p>
      </div>
    </template>

    <el-dialog v-model="deprecating" title="废弃工具" width="440px">
      <el-form label-width="90px">
        <el-form-item label="替代工具"><el-input v-model="form.replacedBy" placeholder="如 work_order_search" /></el-form-item>
        <el-form-item label="截止日期">
          <el-date-picker v-model="form.deadline" type="date" value-format="YYYY-MM-DD" placeholder="依赖方须在此前迁移" />
        </el-form-item>
      </el-form>
      <template #footer>
        <button class="btn" @click="deprecating = false">取消</button>
        <button class="btn pri" style="margin-left: 8px" @click="submitDeprecate">确认废弃</button>
      </template>
    </el-dialog>
    <el-dialog v-model="publishing" title="发布新版本" width="440px">
      <el-form label-width="90px">
        <el-form-item label="版本"><el-input v-model="versionForm.version" placeholder="如 v2" /></el-form-item>
        <el-form-item label="变更说明"><el-input v-model="versionForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="破坏兼容">
          <el-checkbox v-model="versionForm.breaking">参数不兼容，必须换新工具名</el-checkbox>
        </el-form-item>
      </el-form>
      <p v-if="versionForm.breaking" class="mut">破坏兼容不能在原名上发版。请到工具列表用新名字注册。</p>
      <template #footer>
        <button class="btn" @click="publishing = false">取消</button>
        <button class="btn pri" style="margin-left: 8px" @click="submitPublish">确认发布</button>
      </template>
    </el-dialog>
  </div>
</template>
