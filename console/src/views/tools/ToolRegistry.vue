<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Pager from '@/components/Pager.vue'
import ToolDrawer from './ToolDrawer.vue'
import ToolStatusPill from './ToolStatusPill.vue'
import { listTools, registerTool as createTool, type ListToolsQuery, type ToolPage } from '@/api/tools'
import { getSkill, listSkills, publishSkill, revokeSkill, upgradeSkill, type SkillDetail, type SkillSummary, type SkillUser } from '@/api/skills'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { RISK, fmtN } from '@/utils/format'
import { cleanCases, cleanExamples, lineDiff, normalizeSkillName, pageSlice, sameSkillContent, SKILL_NAME_HINT, skillNameError, sourceLabel, splitList, versionMeta, type SkillCaseDraft } from './skillView'

const ACCESS = { READ: '读', WRITE: '写', EXEC: '执行' } as const
const SCOPE = { PRIVATE: '私有', SHARED: '共享' } as const

const route = useRoute()
const router = useRouter()
const envStore = useEnvStore()
const result = ref<ToolPage | null>(null)
const loading = ref(false)
const registering = ref(false)
const tab = ref<'tool' | 'skill'>(route.query.skill || route.query.tab === 'skill' ? 'skill' : 'tool')
const skills = ref<SkillSummary[]>([])
const detail = ref<SkillDetail | null>(null)
const selectedVersion = ref<number | null>(null)
const publishing = ref(false)
const nameInvalid = ref(false)
const editing = ref(false)
const noteInvalid = ref(false)
const savingSkill = ref(false)
const seenStatus = new Map<string, string>()
let pollTimer: ReturnType<typeof setInterval> | undefined
const skillPager = reactive({ page: 1, size: 10 as 10 | 20 | 50 | 100 })
const skillForm = reactive({
  name: '',
  ownerOrg: '',
  description: '',
  instructions: '',
  tools: '',
  examples: [''] as string[],
  cases: [{ input: '', expectedOutput: '' }] as SkillCaseDraft[],
  source: 'AUTHORED' as 'AUTHORED' | 'EXPERIENCE',
})
const editForm = reactive({
  instructions: '',
  note: '',
  examples: [''] as string[],
  cases: [{ input: '', expectedOutput: '' }] as SkillCaseDraft[],
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

const selectedSkill = computed(() => typeof route.query.skill === 'string' ? route.query.skill : '')
const pagedSkills = computed(() => pageSlice(skills.value, skillPager.page, skillPager.size))
const versions = computed(() => [...(detail.value?.versions ?? [])].sort((left, right) => right.version - left.version))
const latestVersion = computed(() => versions.value.find((item) => item.version === detail.value?.latest) ?? versions.value[0] ?? null)
const currentVersion = computed(() => versions.value.find((item) => item.version === selectedVersion.value) ?? latestVersion.value)
const previousVersion = computed(() => {
  const ordered = [...(detail.value?.versions ?? [])].sort((left, right) => left.version - right.version)
  const index = ordered.findIndex((item) => item.version === currentVersion.value?.version)
  return index > 0 ? ordered[index - 1] : null
})
const diffTitle = computed(() => {
  if (!currentVersion.value) return ''
  return previousVersion.value
    ? `v${previousVersion.value.version} → v${currentVersion.value.version}`
    : `v${currentVersion.value.version}（初版）`
})
const diffLines = computed(() => {
  if (!currentVersion.value) return []
  return previousVersion.value
    ? lineDiff(previousVersion.value.instructions, currentVersion.value.instructions)
    : currentVersion.value.instructions.split('\n').map((text) => ({ kind: 'same' as const, text }))
})

async function loadSkills() {
  try {
    skills.value = (await listSkills()).items ?? []
    const pages = Math.max(1, Math.ceil(skills.value.length / skillPager.size))
    if (skillPager.page > pages) skillPager.page = 1
  } catch (error) {
    ElMessage.error(`加载技能失败：${toKeelError(error).message}`)
  }
}

function rememberUsers(users: SkillUser[]) {
  for (const user of users) {
    const before = seenStatus.get(user.agent)
    if (before === 'RUNNING' && user.status === 'LATEST') {
      const score = user.score == null ? '' : ` ${user.score.toFixed(2)} ≥ ${(user.threshold ?? 0.85).toFixed(2)}`
      ElMessage.success(`${user.agent} 回归通过${score}，待重新部署后生效`)
    } else if (before === 'RUNNING' && user.status !== 'RUNNING') {
      ElMessage.warning(user.upgradeError || `${user.agent} 回归未通过，仍锁定 v${user.version}`)
    }
    if (user.status) seenStatus.set(user.agent, user.status)
  }
}

function stopPoll() {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = undefined
}

function startPoll() {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    if (selectedSkill.value) loadDetail()
  }, 2000)
}

async function loadDetail() {
  if (!selectedSkill.value) {
    detail.value = null
    stopPoll()
    return
  }
  try {
    const next = await getSkill(selectedSkill.value)
    rememberUsers(next.users ?? [])
    detail.value = next
    if (selectedVersion.value == null || !(next.versions ?? []).some((item) => item.version === selectedVersion.value)) {
      selectedVersion.value = next.latest
    }
    if ((next.users ?? []).some((user) => user.status === 'RUNNING')) startPoll()
    else stopPoll()
  } catch (error) {
    ElMessage.error(`加载技能失败：${toKeelError(error).message}`)
  }
}

function selectSkill(name: string) {
  tab.value = 'skill'
  selectedVersion.value = null
  seenStatus.clear()
  router.push({ query: { ...route.query, tab: 'skill', skill: name } })
}

function showRegistry() {
  stopPoll()
  detail.value = null
  const query = { ...route.query }
  query.tab = 'skill'
  delete query.skill
  router.push({ query })
}

function openSkillList() {
  tab.value = 'skill'
  showRegistry()
}

function openToolList() {
  tab.value = 'tool'
  stopPoll()
  const query = { ...route.query }
  delete query.skill
  delete query.tab
  router.push({ query })
}

function openPublish() {
  skillForm.name = ''
  skillForm.ownerOrg = ''
  skillForm.description = ''
  skillForm.instructions = ''
  skillForm.tools = ''
  skillForm.examples = ['']
  skillForm.cases = [{ input: '', expectedOutput: '' }]
  skillForm.source = 'AUTHORED'
  nameInvalid.value = false
  publishing.value = true
}

async function submitSkill() {
  const name = normalizeSkillName(skillForm.name)
  const nameError = skillNameError(skillForm.name)
  if (nameError) {
    nameInvalid.value = true
    ElMessage.warning(nameError)
    return
  }
  if (!skillForm.ownerOrg.trim() || !skillForm.description.trim() || !skillForm.instructions.trim()) {
    ElMessage.warning('所有者、说明和正文都不能为空')
    return
  }
  try {
    const saved = await publishSkill({
      name,
      ownerOrg: skillForm.ownerOrg.trim(),
      description: skillForm.description.trim(),
      instructions: skillForm.instructions.trim(),
      source: skillForm.source,
      tools: splitList(skillForm.tools),
      examples: cleanExamples(skillForm.examples),
      cases: cleanCases(skillForm.cases),
    })
    publishing.value = false
    ElMessage.success(`已发布 ${name} v${saved.version}，没有授予工具`)
    await loadSkills()
    selectSkill(name)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

function openEdit() {
  const latest = latestVersion.value
  if (!latest) return
  editForm.instructions = latest.instructions
  editForm.note = ''
  editForm.examples = (latest.examples ?? []).length ? [...(latest.examples ?? [])] : ['']
  editForm.cases = (latest.cases ?? []).length
    ? (latest.cases ?? []).map((item) => ({ id: item.id, input: item.input, expectedOutput: item.expectedOutput ?? '' }))
    : [{ input: '', expectedOutput: '' }]
  noteInvalid.value = false
  editing.value = true
}

async function saveSkill() {
  const latest = latestVersion.value
  const skill = detail.value
  if (!latest || !skill) return
  const note = editForm.note.trim()
  if (!note) {
    noteInvalid.value = true
    return
  }
  const examples = cleanExamples(editForm.examples)
  const cases = cleanCases(editForm.cases)
  const instructions = editForm.instructions.trim()
  if (sameSkillContent(
    { instructions, tools: latest.tools ?? [], examples, cases },
    { instructions: latest.instructions, tools: latest.tools ?? [], examples: latest.examples ?? [], cases: latest.cases ?? [] },
  )) {
    ElMessage.warning('内容没有变化，不生成新版本')
    return
  }
  savingSkill.value = true
  try {
    const saved = await publishSkill({
      name: skill.name,
      ownerOrg: skill.ownerOrg ?? '',
      description: skill.description ?? '',
      instructions,
      source: skill.source,
      tools: latest.tools ?? [],
      examples,
      cases,
      note,
    })
    editing.value = false
    selectedVersion.value = saved.version ?? null
    ElMessage.success(`已发布 v${saved.version}，使用方不会自动升级`)
    await loadSkills()
    await loadDetail()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    savingSkill.value = false
  }
}

async function upgrade(user: SkillUser) {
  if (!detail.value) return
  try {
    await upgradeSkill(user.agent, detail.value.name)
    ElMessage.info(`${user.agent}：已改 spec.skills 为 v${detail.value.latest}，回归运行中…`)
    await loadDetail()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

async function revoke(user: SkillUser) {
  if (!detail.value) return
  try {
    await revokeSkill(user.agent, detail.value.name)
    ElMessage.warning(`已撤销 ${user.agent} 的技能授权；依赖工具授权不受影响`)
    await loadSkills()
    await loadDetail()
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

onUnmounted(stopPoll)

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

async function openSkillTab() {
  await loadSkills()
  if (selectedSkill.value) await loadDetail()
  else {
    detail.value = null
    stopPoll()
  }
}

watch(tab, (value) => {
  if (value === 'skill') openSkillTab()
  else stopPoll()
})
watch(selectedSkill, (name) => {
  if (tab.value !== 'skill') return
  if (name) loadDetail()
  else {
    detail.value = null
    stopPoll()
  }
})
if (tab.value === 'skill') openSkillTab()
else loadSkills()
watch(() => [filter.page, filter.size], load)
watch(() => envStore.env, () => {
  if (filter.page === 1) load()
  else filter.page = 1
}, { immediate: true })
watch(() => skillPager.size, () => { skillPager.page = 1 })
</script>

<template>
  <div>
    <div class="vh">
      <h2>工具与技能</h2>
      <span v-if="tab === 'tool'" class="sub">统一注册，声明读写属性和风险等级。高风险工具的每次调用都会被挂起，送到审批中心</span>
      <span v-else-if="selectedSkill" class="sub">技能 › <b>{{ selectedSkill }}</b></span>
      <span class="sp" />
      <button v-if="tab === 'skill' && selectedSkill" class="btn" type="button" @click="showRegistry">← 注册表</button>
      <button v-if="tab === 'tool'" v-write class="btn pri" @click="openRegister">+ 注册 MCP 工具</button>
      <button v-else v-write class="btn pri" @click="openPublish">+ 发布技能</button>
    </div>
    <div class="chipsel" style="margin-bottom: 14px">
      <button :class="{ on: tab === 'tool' }" @click="openToolList">工具（tool）· {{ result?.total ?? 0 }}</button>
      <button :class="{ on: tab === 'skill' }" @click="openSkillList">技能（skill）· {{ skills.length }}</button>
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
    <div v-else-if="!selectedSkill" class="card">
      <table class="t">
        <thead><tr><th>技能</th><th>所有者</th><th>版本</th><th>依赖工具</th><th>风险</th><th>使用方</th><th>来源</th></tr></thead>
        <tbody>
          <tr v-if="!skills.length"><td colspan="7" class="empty">还没有技能</td></tr>
          <tr v-for="skill in pagedSkills" :key="skill.name" class="click" @click="selectSkill(skill.name)">
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
      <Pager v-model:page="skillPager.page" v-model:size="skillPager.size" :total="skills.length" />
    </div>
    <div v-if="tab === 'skill' && selectedSkill && detail" class="row2 skill">
      <div class="card">
        <h3>版本 <small>点击查看，与上一版对比</small><span class="sp" /><button v-write class="btn pri sm" type="button" @click="openEdit">编辑（另存为新版本）</button></h3>
        <div class="skill-split">
          <div class="vlist">
            <div v-for="item in versions" :key="item.version" class="vi" :class="{ on: item.version === currentVersion?.version }" @click="selectedVersion = item.version">
              <b>v{{ item.version }}</b>
              <span v-if="item.version === detail.latest" class="pill nd p-ok">最新</span>
              <span v-else class="pill nd p-mute">只读</span>
              <div class="m">{{ versionMeta(item) }}</div>
            </div>
          </div>
          <div>
            <div class="mut" style="font-size: 12px; margin-bottom: 6px">{{ diffTitle }}</div>
            <div class="diff code" style="padding: 8px 0; background: #0b1220; border: 1px solid var(--line)">
              <div v-for="(line, index) in diffLines" :key="index" :class="line.kind">{{ line.text }}</div>
            </div>
          </div>
        </div>
      </div>
      <div class="card">
        <h3>使用方</h3>
        <table class="t">
          <thead><tr><th>智能体</th><th>锁定版本</th><th>状态</th><th></th></tr></thead>
          <tbody>
            <tr v-if="!(detail.users ?? []).length"><td colspan="4" class="mut">没有使用方</td></tr>
            <tr v-for="user in detail.users ?? []" :key="user.agent">
              <td class="nm"><b>{{ user.agent }}</b></td>
              <td class="mono">v{{ user.version }}</td>
              <td>
                <span v-if="user.status === 'RUNNING'" class="pill p-soft">回归中</span>
                <span v-else-if="user.status === 'LATEST'" class="pill p-ok">最新</span>
                <span v-else class="pill p-warn">有新版本 v{{ detail.latest }}</span>
              </td>
              <td style="text-align: right; white-space: nowrap">
                <button v-if="user.version < detail.latest && user.status !== 'RUNNING'" v-write class="btn sm" type="button" @click="upgrade(user)">升级并跑回归</button>
                <button v-write class="btn sm danger" type="button" style="margin-left: 6px" @click="revoke(user)">撤销授权</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="pbanner warn" style="font-size: 12px; margin-top: 10px">升级 = 改 agent.yaml 的 spec.skills 版本 → 跑回归 → 通过后重新部署。未升级的智能体继续用锁定版本。</div>
      </div>
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
    <div v-if="publishing" class="mask on" @click="publishing = false" />
    <div v-if="publishing" class="modal wide on" role="dialog" aria-label="发布技能">
      <div class="mh">发布技能</div>
      <div class="mb">
        <div class="field">
          <label>技能名</label>
          <input v-model="skillForm.name" class="inp" :class="{ err: nameInvalid }" placeholder="如 cite-from-kb" @input="nameInvalid = false" />
          <div class="mut" style="font-size: 12px; margin-top: 4px">{{ SKILL_NAME_HINT }}</div>
        </div>
        <div class="field"><label>所有者</label><input v-model="skillForm.ownerOrg" class="inp" /></div>
        <div class="field"><label>说明</label><input v-model="skillForm.description" class="inp" /></div>
        <div class="field"><label>正文</label><textarea v-model="skillForm.instructions" class="inp" /></div>
        <div class="field"><label>依赖工具</label><input v-model="skillForm.tools" class="inp" placeholder="逗号分隔，须已注册" /></div>
        <div class="field">
          <label>示例</label>
          <div v-for="(_, index) in skillForm.examples" :key="index" style="display: flex; gap: 8px; margin-bottom: 6px">
            <input v-model="skillForm.examples[index]" class="inp" />
            <button class="btn sm" type="button" @click="skillForm.examples.splice(index, 1)">删除</button>
          </div>
          <button class="btn sm" type="button" @click="skillForm.examples.push('')">+ 添加示例</button>
        </div>
        <div class="field">
          <label>用例</label>
          <div v-for="(item, index) in skillForm.cases" :key="index" style="display: grid; grid-template-columns: 1fr 1fr auto; gap: 8px; margin-bottom: 6px">
            <input v-model="item.input" class="inp" placeholder="输入" />
            <input v-model="item.expectedOutput" class="inp" placeholder="期望输出" />
            <button class="btn sm" type="button" @click="skillForm.cases.splice(index, 1)">删除</button>
          </div>
          <button class="btn sm" type="button" @click="skillForm.cases.push({ input: '', expectedOutput: '' })">+ 添加用例</button>
        </div>
        <div class="field">
          <label>来源</label>
          <select v-model="skillForm.source" class="inp">
            <option value="AUTHORED">人工编写</option>
            <option value="EXPERIENCE">经验沉淀</option>
          </select>
        </div>
      </div>
      <div class="mf">
        <button class="btn" type="button" @click="publishing = false">取消</button>
        <button v-write class="btn pri" type="button" @click="submitSkill">发布</button>
      </div>
    </div>
    <div v-if="editing" class="mask on" @click="editing = false" />
    <div v-if="editing" class="modal wide on" role="dialog" aria-label="编辑技能">
      <div class="mh">编辑 {{ detail?.name }} · 另存为 v{{ (detail?.latest ?? 0) + 1 }}</div>
      <div class="mb">
        <div class="field"><label>正文</label><textarea v-model="editForm.instructions" class="inp" style="min-height: 110px" /></div>
        <div class="field"><label>变更说明</label><input v-model="editForm.note" class="inp" :class="{ err: noteInvalid }" placeholder="必填，例如：拒答时附上可咨询的人" @input="noteInvalid = false" /></div>
        <div class="field">
          <label>示例</label>
          <div v-for="(_, index) in editForm.examples" :key="index" style="display: flex; gap: 8px; margin-bottom: 6px">
            <input v-model="editForm.examples[index]" class="inp" />
            <button class="btn sm" type="button" @click="editForm.examples.splice(index, 1)">删除</button>
          </div>
          <button class="btn sm" type="button" @click="editForm.examples.push('')">+ 添加示例</button>
        </div>
        <div class="field">
          <label>用例</label>
          <div v-for="(item, index) in editForm.cases" :key="index" style="display: grid; grid-template-columns: 1fr 1fr auto; gap: 8px; margin-bottom: 6px">
            <input v-model="item.input" class="inp" placeholder="输入" />
            <input v-model="item.expectedOutput" class="inp" placeholder="期望输出" />
            <button class="btn sm" type="button" @click="editForm.cases.splice(index, 1)">删除</button>
          </div>
          <button class="btn sm" type="button" @click="editForm.cases.push({ input: '', expectedOutput: '' })">+ 添加用例</button>
        </div>
        <div class="mut" style="font-size: 12px">保存后旧版本只读；使用方不会自动升级。</div>
      </div>
      <div class="mf">
        <button class="btn" type="button" @click="editing = false">取消</button>
        <button v-write class="btn pri" type="button" :disabled="savingSkill" @click="saveSkill">保存为新版本</button>
      </div>
    </div>
    <ToolDrawer @changed="load" />
  </div>
</template>

