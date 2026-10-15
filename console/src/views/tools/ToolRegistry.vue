<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import ToolDrawer from './ToolDrawer.vue'
import ToolStatusPill from './ToolStatusPill.vue'
import { listTools, registerTool as createTool, type ListToolsQuery, type ToolPage } from '@/api/tools'
import { listSkills, publishSkill, type SkillSummary } from '@/api/skills'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { RISK, fmtN } from '@/utils/format'
import { sourceLabel, splitList } from './skillView'

const ACCESS = { READ: '读', WRITE: '写', EXEC: '执行' } as const
const SCOPE = { PRIVATE: '私有', SHARED: '共享' } as const

const route = useRoute()
const router = useRouter()
const envStore = useEnvStore()
const result = ref<ToolPage | null>(null)
const loading = ref(false)
const registering = ref(false)
const tab = ref<'tool' | 'skill'>('tool')
const skills = ref<SkillSummary[]>([])
const publishing = ref(false)
const skillForm = reactive({
  name: '',
  ownerOrg: '',
  description: '',
  instructions: '',
  tools: '',
  example: '',
  caseInput: '',
  caseOutput: '',
  source: 'AUTHORED' as 'AUTHORED' | 'EXPERIENCE',
})
const form = reactive({
  name: '',
  description: '',
  scope: 'PRIVATE' as 'PRIVATE' | 'SHARED',
  access: 'READ' as 'READ' | 'WRITE' | 'EXEC',
  risk: 'LOW' as 'LOW' | 'MID' | 'HIGH',
  provider: '',
  ownerAgent: '',
  schemaText: '{"type":"object"}',
})
const filter = reactive<{ scope: NonNullable<ListToolsQuery['scope']>; page: number; size: NonNullable<ListToolsQuery['size']> }>({
  scope: 'all',
  page: 1,
  size: 10,
})

async function loadSkills() {
  try {
    skills.value = (await listSkills()).items ?? []
  } catch (error) {
    ElMessage.error(`加载技能失败：${toKeelError(error).message}`)
  }
}

function openPublish() {
  skillForm.name = ''
  skillForm.ownerOrg = ''
  skillForm.description = ''
  skillForm.instructions = ''
  skillForm.tools = ''
  skillForm.example = ''
  skillForm.caseInput = ''
  skillForm.caseOutput = ''
  skillForm.source = 'AUTHORED'
  publishing.value = true
}

async function submitSkill() {
  if (!skillForm.name.trim() || !skillForm.ownerOrg.trim() || !skillForm.description.trim() || !skillForm.instructions.trim()) {
    ElMessage.warning('技能名、所有者、说明和正文都不能为空')
    return
  }
  try {
    const saved = await publishSkill({
      name: skillForm.name.trim(),
      ownerOrg: skillForm.ownerOrg.trim(),
      description: skillForm.description.trim(),
      instructions: skillForm.instructions.trim(),
      source: skillForm.source,
      tools: splitList(skillForm.tools),
      examples: splitList(skillForm.example),
      cases: skillForm.caseInput.trim() ? [{ input: skillForm.caseInput.trim(), expectedOutput: skillForm.caseOutput.trim() }] : [],
    })
    publishing.value = false
    ElMessage.success(`已发布 ${skillForm.name.trim()} v${saved.version}，没有授予工具`)
    await loadSkills()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

async function load() {
  loading.value = true
  try {
    result.value = await listTools({ scope: filter.scope, env: envStore.env, page: filter.page, size: filter.size })
  } catch (error) {
    ElMessage.error(`加载工具失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

function openTool(name?: string) {
  if (!name) return
  router.push({ query: { ...route.query, tool: name } })
}

function setScope(scope: typeof filter.scope) {
  filter.scope = scope
  filter.page = 1
  load()
}

function openRegister() {
  form.name = ''
  form.description = ''
  form.scope = 'PRIVATE'
  form.access = 'READ'
  form.risk = 'LOW'
  form.provider = ''
  form.ownerAgent = ''
  form.schemaText = '{"type":"object"}'
  registering.value = true
}

async function submitRegister() {
  if (!form.name.trim() || !form.provider.trim()) {
    ElMessage.warning('工具名和 MCP 地址不能为空')
    return
  }
  let schemaJson: Record<string, unknown>
  try {
    schemaJson = JSON.parse(form.schemaText) as Record<string, unknown>
  } catch {
    ElMessage.warning('参数 schema 不是合法 JSON')
    return
  }
  try {
    await createTool({
      name: form.name.trim(),
      description: form.description.trim(),
      scope: form.scope,
      access: form.access,
      risk: form.risk,
      provider: form.provider.trim(),
      ownerAgent: form.ownerAgent.trim() || undefined,
      schemaJson,
    })
    registering.value = false
    ElMessage.success('已注册')
    await load()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

watch(tab, () => {
  if (tab.value === 'skill') loadSkills()
})
watch(() => [filter.page, filter.size], load)
watch(() => envStore.env, () => {
  if (filter.page === 1) load()
  else filter.page = 1
}, { immediate: true })
</script>

<template>
  <div>
    <div class="vh">
      <h2>工具与技能</h2>
      <span class="sub">统一注册，声明读写属性和风险等级。高风险工具的每次调用都会被挂起，送到审批中心</span>
      <span class="sp" />
      <button v-if="tab === 'tool'" v-write class="btn pri" @click="openRegister">+ 注册 MCP 工具</button>
      <button v-else v-write class="btn pri" @click="openPublish">+ 发布技能</button>
    </div>
    <div class="chipsel" style="margin-bottom: 14px">
      <button :class="{ on: tab === 'tool' }" @click="tab = 'tool'">工具 · {{ result?.total ?? 0 }}</button>
      <button :class="{ on: tab === 'skill' }" @click="tab = 'skill'">技能 · {{ skills.length }}</button>
    </div>
    <div v-if="tab === 'tool'" class="toolbar">
      <div class="chipsel">
        <button :class="{ on: filter.scope === 'all' }" @click="setScope('all')">全部</button>
        <button :class="{ on: filter.scope === 'SHARED' }" @click="setScope('SHARED')">共享</button>
        <button :class="{ on: filter.scope === 'PRIVATE' }" @click="setScope('PRIVATE')">私有</button>
      </div>
    </div>
    <div v-if="tab === 'tool'" v-loading="loading" class="card">
      <table class="t">
        <thead><tr><th>工具</th><th>范围</th><th>所有者</th><th>读写</th><th>风险</th><th>版本</th><th>依赖方</th><th>24h</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="t in result?.items ?? []" :key="t.name" class="click" @click="openTool(t.name)">
            <td class="nm"><b class="mono">{{ t.name }}</b><small>{{ t.description }}</small></td>
            <td>{{ SCOPE[t.scope!] }}</td>
            <td>{{ t.ownerAgent ?? t.ownerOrg }}</td>
            <td>{{ ACCESS[t.access!] }}</td>
            <td><span class="pill nd" :class="RISK[t.risk!].cls">{{ RISK[t.risk!].label }}</span></td>
            <td class="mono">{{ t.version }}</td>
            <td><span v-if="t.dependentCount" class="mono">{{ t.dependentCount }}</span><span v-else class="mut">无</span></td>
            <td class="mono">{{ fmtN(t.calls24h) }}</td>
            <td>
              <ToolStatusPill :status="t.status" />
              <div v-if="t.status === 'DEPRECATED'" class="mut" style="font-size: 11px">→ {{ t.replacedBy }} · {{ t.deprecateDeadline }}</div>
            </td>
          </tr>
        </tbody>
      </table>
      <Pager v-model:page="filter.page" v-model:size="filter.size" :total="result?.total ?? 0" />
    </div>
    <div v-else class="card">
      <table class="t">
        <thead><tr><th>技能</th><th>所有者</th><th>版本</th><th>依赖工具</th><th>风险</th><th>使用方</th><th>来源</th></tr></thead>
        <tbody>
          <tr v-if="!skills.length"><td colspan="7" class="empty">还没有技能</td></tr>
          <tr v-for="skill in skills" :key="skill.name">
            <td class="nm"><b class="mono">{{ skill.name }}</b><small>{{ skill.description }}</small></td>
            <td>{{ skill.ownerOrg }}</td>
            <td class="mono">v{{ skill.version }}</td>
            <td><span v-for="tool in skill.tools ?? []" :key="tool" class="chip">{{ tool }}</span><span v-if="!(skill.tools ?? []).length" class="mut">无</span></td>
            <td><span v-if="skill.risk" class="pill nd" :class="RISK[skill.risk].cls">{{ RISK[skill.risk].label }}</span></td>
            <td><span v-for="user in skill.users ?? []" :key="user" class="chip">{{ user }}</span><span v-if="!(skill.users ?? []).length" class="mut">无</span></td>
            <td>{{ sourceLabel(skill.source) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <el-dialog v-model="registering" title="注册 MCP 工具" width="480px">
      <el-form label-width="100px">
        <el-form-item label="工具名"><el-input v-model="form.name" placeholder="如 echo.note" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" /></el-form-item>
        <el-form-item label="MCP 地址"><el-input v-model="form.provider" placeholder="mcp:// 或 https://" /></el-form-item>
        <el-form-item label="所有者"><el-input v-model="form.ownerAgent" placeholder="智能体名，可空" /></el-form-item>
        <el-form-item label="范围">
          <el-select v-model="form.scope" style="width: 100%">
            <el-option label="私有" value="PRIVATE" />
            <el-option label="共享" value="SHARED" />
          </el-select>
        </el-form-item>
        <el-form-item label="读写">
          <el-select v-model="form.access" style="width: 100%">
            <el-option label="读" value="READ" />
            <el-option label="写" value="WRITE" />
            <el-option label="执行" value="EXEC" />
          </el-select>
        </el-form-item>
        <el-form-item label="风险">
          <el-select v-model="form.risk" style="width: 100%">
            <el-option label="低" value="LOW" />
            <el-option label="中" value="MID" />
            <el-option label="高" value="HIGH" />
          </el-select>
        </el-form-item>
        <el-form-item label="参数 schema"><el-input v-model="form.schemaText" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <button class="btn" @click="registering = false">取消</button>
        <button v-write class="btn pri" style="margin-left: 8px" @click="submitRegister">确认注册</button>
      </template>
    </el-dialog>
    <el-dialog v-model="publishing" title="发布技能" width="520px">
      <el-form label-width="100px">
        <el-form-item label="技能名"><el-input v-model="skillForm.name" placeholder="如 cite-from-kb" /></el-form-item>
        <el-form-item label="所有者"><el-input v-model="skillForm.ownerOrg" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="skillForm.description" /></el-form-item>
        <el-form-item label="正文"><el-input v-model="skillForm.instructions" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="依赖工具"><el-input v-model="skillForm.tools" placeholder="逗号分隔，须已注册" /></el-form-item>
        <el-form-item label="示例"><el-input v-model="skillForm.example" /></el-form-item>
        <el-form-item label="用例输入"><el-input v-model="skillForm.caseInput" /></el-form-item>
        <el-form-item label="期望输出"><el-input v-model="skillForm.caseOutput" /></el-form-item>
        <el-form-item label="来源">
          <el-select v-model="skillForm.source" style="width: 100%">
            <el-option label="人工编写" value="AUTHORED" />
            <el-option label="经验沉淀" value="EXPERIENCE" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <button class="btn" @click="publishing = false">取消</button>
        <button v-write class="btn pri" style="margin-left: 8px" @click="submitSkill">发布</button>
      </template>
    </el-dialog>
    <ToolDrawer @changed="load" />
  </div>
</template>
