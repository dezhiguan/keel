<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { diffPrompt, getPrompt, getPromptVersion, promotePrompt, rollbackPrompt, savePromptVersion, trialPrompt, type PromptBody, type PromptConstraintNote, type PromptDiff } from '@/api/prompts'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import {
  banners, canEdit, canRollback, configDraft, configPayload, draftError, editingDrafts, formatWhen, highlightParts, PR_ENVS, PR_LABEL, promotableEnvs, removedConstraintMessage, removedConstraints, rolesFor, SECTION_LABEL, shortSha, structureOf, unchangedPrompt, variablesOf,
  type ConfigDraft, type EnvName, type PromptDetailModel, type PromptSection, type PromptStructure, type PromptVariableSchema,
} from './promptView'

const route = useRoute()
const router = useRouter()
const envStore = useEnvStore()
const detail = ref<PromptDetailModel | null>(null)
const missing = ref(false)
const loading = ref(false)
const selected = ref<number | null>(null)
const compare = ref('')
const body = ref<PromptBody | null>(null)
const diff = ref<PromptDiff | null>(null)
const editing = ref(false)
const rolling = ref(false)
const tab = ref<'ver' | 'struct' | 'vars' | 'run'>('ver')
const commitMessage = ref('')
const reason = ref('')
const drafts = ref<{ role: string; content: string }[]>([])
const config = ref<ConfigDraft>({ temperature: '', maxTokens: '', topP: '' })
const messageInvalid = ref(false)
const removed = ref<PromptConstraintNote[]>([])
const structuring = ref(false)
const draftSections = ref<PromptSection[]>([])
const schemaEditing = ref(false)
const schemaRows = ref<PromptVariableSchema[]>([])
const draftContract = ref('')
const contractInvalid = ref(false)
const trialInputs = ref<Record<string, string>>({})
const trialText = ref('')
const trialError = ref('')

const agent = computed(() => String(route.params.agent ?? ''))
const name = computed(() => String(route.params.name ?? ''))
const versions = computed(() => [...(detail.value?.versions ?? [])].reverse())
const current = computed(() => versions.value.find((version) => version.version === selected.value) ?? null)
const homeEnv = computed(() => (envStore.env === 'all' ? 'staging' : envStore.env))
const editable = computed(() => !!detail.value?.declared && canEdit(envStore.env))
const targets = computed(() => promotableEnvs(envStore.env, selected.value, detail.value?.labs))
const rollbackOk = computed(() => canRollback(envStore.env, selected.value, detail.value?.labs, detail.value?.releases))
const notes = computed(() => (detail.value ? banners(detail.value, envStore.env) : []))
const variables = computed(() => body.value ? (body.value.variables ?? variablesOf(body.value.prompt)) : [])
const shownConfig = computed(() => body.value?.config ?? detail.value?.config ?? {})
const configText = computed(() => Object.entries(shownConfig.value)
  .filter(([key, value]) => key !== 'keel' && (typeof value === 'string' || typeof value === 'number'))
  .map(([key, value]) => `${key}=${value}`).join(' · ') || '—')
const structure = computed(() => body.value?.structure ?? structureOf(shownConfig.value))
const sections = computed(() => structure.value.sections ?? [])
const schemaVariables = computed(() => structure.value.variables ?? [])
const samples = computed(() => structure.value.samples ?? [])
const trialNames = computed(() => {
  const names = [...variables.value]
  for (const item of schemaVariables.value) {
    if (item.required !== false && item.name && !names.includes(item.name)) names.push(item.name)
  }
  return names
})
const previousVersion = computed(() => {
  const ordered = [...(detail.value?.versions ?? [])].map((item) => item.version).sort((left, right) => left - right)
  const index = ordered.indexOf(selected.value ?? -1)
  return index > 0 ? ordered[index - 1] : null
})
const removedText = computed(() => removedConstraintMessage(removed.value))
const draftRemovedText = computed(() => removedConstraintMessage(removedConstraints(sections.value, draftSections.value)))
const draftVariables = computed(() => variablesOf(detail.value?.type === 'chat' ? drafts.value : drafts.value[0]?.content ?? ''))
const promptType = computed(() => detail.value?.type === 'chat' ? 'chat' : 'text')
function braces(variable: string) {
  return `{{${variable}}}`
}

function showLabel(label: string) {
  const env = label === 'production' ? 'prod' : label
  return (envStore.env === 'all' || envStore.env === env) && PR_ENVS.includes(env as EnvName)
}

function labelClass(label: string) {
  if (label === 'production') return 'p-ok'
  if (label === 'staging') return 'p-acc'
  return 'p-soft'
}

async function load(prefer?: number) {
  loading.value = true
  missing.value = false
  try {
    detail.value = await getPrompt(agent.value, name.value)
    const list = versions.value
    const home = detail.value.labs?.[homeEnv.value] ?? detail.value.labs?.staging ?? list[0]?.version ?? null
    selected.value = prefer ?? (list.some((version) => version.version === selected.value) ? selected.value : home)
    await loadBody()
  } catch (error) {
    detail.value = null
    const keel = toKeelError(error)
    if (keel.code === 'PROMPT_NOT_DECLARED' || keel.code === 'SERVER_NOT_FOUND') missing.value = true
    else ElMessage.error(`加载提示词失败：${keel.message}`)
  } finally {
    loading.value = false
  }
}

async function loadBody() {
  body.value = null
  diff.value = null
  if (!detail.value || selected.value == null) return
  try {
    body.value = await getPromptVersion(agent.value, name.value, selected.value)
    if (compare.value) {
      diff.value = await diffPrompt(agent.value, name.value, Number(compare.value), selected.value)
    }
    if (tab.value === 'struct') await loadRemoved()
  } catch (error) {
    ElMessage.error(`读取版本失败：${toKeelError(error).message}`)
  }
}

async function loadRemoved() {
  removed.value = []
  if (previousVersion.value == null || selected.value == null) return
  try {
    const result = await diffPrompt(agent.value, name.value, previousVersion.value, selected.value)
    removed.value = result.removedConstraints ?? []
  } catch (error) {
    ElMessage.error(`读取约束差异失败：${toKeelError(error).message}`)
  }
}

function pick(version: number) {
  selected.value = version
  compare.value = ''
  loadBody()
}

function messagesOf(prompt: PromptBody['prompt'] | undefined) {
  if (!prompt) return []
  if (typeof prompt === 'string') return [{ role: 'text', content: prompt }]
  return prompt.map((message) => ({ role: message.role, content: message.content }))
}

function openEdit() {
  if (!detail.value) return
  drafts.value = editingDrafts(promptType.value, body.value?.prompt).map((message) => ({ ...message }))
  config.value = configDraft(body.value?.config ?? detail.value.config)
  commitMessage.value = ''
  messageInvalid.value = false
  editing.value = true
}

function addMessage() {
  drafts.value.push({ role: 'user', content: '' })
}

function removeMessage(index: number) {
  if (drafts.value.length < 2) return
  drafts.value.splice(index, 1)
}

async function save() {
  if (!commitMessage.value.trim()) {
    messageInvalid.value = true
    ElMessage.error('请填写提交说明')
    return
  }
  const structure = draftError(promptType.value, drafts.value)
  if (structure) {
    ElMessage.error(structure)
    return
  }
  const parsed = configPayload(config.value, structureOf(body.value?.config))
  if ('error' in parsed) {
    ElMessage.error(parsed.error)
    return
  }
  const prompt = promptType.value === 'chat'
    ? drafts.value.map((draft) => ({ role: draft.role, content: draft.content }))
    : drafts.value[0]?.content ?? ''
  if (unchangedPrompt(body.value?.prompt, body.value?.config, prompt, parsed.config)) {
    ElMessage.info('内容和参数都没有变化')
    return
  }
  try {
    const saved = await savePromptVersion(agent.value, name.value, {
      prompt,
      commitMessage: commitMessage.value.trim(),
      env: envStore.env === 'all' ? 'staging' : envStore.env,
      config: parsed.config,
    })
    editing.value = false
    structuring.value = false
    schemaEditing.value = false
    const note = removedConstraintMessage(saved.removedConstraints ?? [])
    ElMessage.success(note || `已保存为 v${saved.version}，哪个环境都还没切过去`)
    await load(saved.version)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

async function promote(env: 'staging') {
  if (selected.value == null) return
  try {
    await promotePrompt(agent.value, name.value, env, selected.value)
    ElMessage.info(`staging 标签已挪到 v${selected.value}，60 秒内生效；请运行评测并检查门禁`)
    await load(selected.value)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

async function confirmRollback() {
  if (selected.value == null) return
  try {
    await rollbackPrompt(agent.value, name.value, selected.value, reason.value)
    rolling.value = false
    ElMessage.warning(`prod 已回滚到 v${selected.value}`)
    await load(selected.value)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

function openStructure() {
  draftSections.value = sections.value.map((section) => ({ ...section }))
  if (!draftSections.value.length) draftSections.value = [{ kind: 'role', text: '' }]
  commitMessage.value = ''
  structuring.value = true
}

function addSection() {
  draftSections.value.push({ kind: 'task', text: '' })
}

function openSchema() {
  schemaRows.value = schemaVariables.value.map((item) => ({ ...item, required: item.required !== false, pii: Boolean(item.pii) }))
  draftContract.value = structure.value.outputContract ? JSON.stringify(structure.value.outputContract, null, 2) : ''
  contractInvalid.value = false
  commitMessage.value = ''
  schemaEditing.value = true
}

function sectionProblem(rows: PromptSection[]) {
  if (!rows.length) return '至少保留一个分段'
  for (const section of rows) {
    if (!section.text.trim()) return '分段正文不能为空'
    if (section.kind === 'constraint' && !section.dimension?.trim()) return '约束必须写评测维度'
  }
  return ''
}

async function saveStructure() {
  if (!commitMessage.value.trim()) {
    messageInvalid.value = true
    ElMessage.error('请填写提交说明')
    return
  }
  const problem = sectionProblem(draftSections.value)
  if (problem) {
    ElMessage.error(problem)
    return
  }
  const parsed = configPayload(configDraft(body.value?.config), {
    ...structure.value,
    sections: draftSections.value.map((section) => ({
      ...section,
      text: section.text.trim(),
      dimension: section.kind === 'constraint' ? section.dimension?.trim() : undefined,
    })),
  })
  if ('error' in parsed) {
    ElMessage.error(parsed.error)
    return
  }
  try {
    const saved = await savePromptVersion(agent.value, name.value, {
      prompt: body.value?.prompt ?? (promptType.value === 'chat' ? [] : ''),
      commitMessage: commitMessage.value.trim(),
      env: envStore.env === 'all' ? 'staging' : envStore.env,
      config: parsed.config,
      fromSections: true,
    })
    structuring.value = false
    const note = removedConstraintMessage(saved.removedConstraints ?? [])
    ElMessage.success(note || `已保存为 v${saved.version}，哪个环境都还没切过去`)
    await load(saved.version)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

async function saveSchema() {
  if (!commitMessage.value.trim()) {
    messageInvalid.value = true
    ElMessage.error('请填写提交说明')
    return
  }
  let outputContract: PromptStructure['outputContract'] = structure.value.outputContract
  const rawContract = draftContract.value.trim()
  if (!rawContract) outputContract = undefined
  else {
    try {
      const parsedContract = JSON.parse(rawContract)
      if (!parsedContract || typeof parsedContract !== 'object' || Array.isArray(parsedContract)) {
        contractInvalid.value = true
        ElMessage.error('输出契约必须是对象')
        return
      }
      outputContract = parsedContract
    } catch {
      contractInvalid.value = true
      ElMessage.error('输出契约不是合法 JSON')
      return
    }
  }
  const keel: PromptStructure = {
    ...structure.value,
    variables: (schemaRows.value ?? []).filter((item) => item.name.trim()).map((item) => ({ ...item, name: item.name.trim() })),
    outputContract,
  }
  const parsed = configPayload(configDraft(body.value?.config), keel)
  if ('error' in parsed) {
    ElMessage.error(parsed.error)
    return
  }
  if (!body.value) return
  if (unchangedPrompt(body.value.prompt, body.value.config, body.value.prompt, parsed.config)) {
    ElMessage.info('变量和输出契约都没有变化')
    return
  }
  try {
    const saved = await savePromptVersion(agent.value, name.value, {
      prompt: body.value.prompt,
      commitMessage: commitMessage.value.trim(),
      env: envStore.env === 'all' ? 'staging' : envStore.env,
      config: parsed.config,
    })
    schemaEditing.value = false
    ElMessage.success(`已保存为 v${saved.version}，哪个环境都还没切过去`)
    await load(saved.version)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

function formatCompiled(compiled: string | { role: string; content: string }[] | undefined) {
  if (typeof compiled === 'string') return compiled
  return (compiled ?? []).map((message) => `[${message.role}]\n${message.content}`).join('\n\n')
}

async function runTrial(variables: Record<string, string>) {
  if (selected.value == null) return
  trialInputs.value = { ...variables }
  try {
    const result = await trialPrompt(agent.value, name.value, selected.value, variables)
    trialError.value = ''
    trialText.value = formatCompiled(result.compiled)
  } catch (error) {
    trialText.value = ''
    trialError.value = toKeelError(error).message
  }
}

watch(() => [agent.value, name.value], () => load(), { immediate: true })
watch(tab, () => {
  if (tab.value === 'struct') loadRemoved()
})
watch(() => envStore.env, () => {
  if (detail.value) load(selected.value ?? undefined)
})
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <button class="btn sm" type="button" @click="router.push('/prompts')">← 提示词列表</button>
      <h2 class="mono">{{ agent }}/{{ name }}</h2>
      <span v-if="detail" class="pill nd p-soft">{{ detail.type }}</span>
      <span class="sp" />
      <a v-if="detail?.metricsUrl" class="btn" :href="detail.metricsUrl" target="_blank" rel="noopener">按版本看指标 ↗</a>
    </div>

    <div v-if="missing" class="card empty">这个提示词没有在智能体的 manifest 里声明</div>

    <template v-else-if="detail">
      <div v-for="(banner, index) in notes" :key="index" class="pbanner" :class="banner.tone">
        <span v-if="banner.title" class="pill" :class="banner.tone === 'bad' ? 'p-bad' : banner.tone === 'warn' ? 'p-warn' : banner.tone === 'ok' ? 'p-acc' : 'p-soft'">{{ banner.title }}</span>
        <span>{{ banner.text }}</span>
        <span class="sp" />
        <button v-if="banner.action?.kind === 'rollback'" v-write class="btn sm" type="button" @click="pick(banner.action.version); rolling = true">{{ banner.action.label }}</button>
        <button v-else-if="banner.action?.kind === 'pick'" class="btn sm" type="button" @click="pick(banner.action.version)">{{ banner.action.label }}</button>
      </div>

      <div class="chipsel" style="margin-bottom: 14px">
        <button :class="{ on: tab === 'ver' }" @click="tab = 'ver'">版本与内容</button>
        <button :class="{ on: tab === 'struct' }" @click="tab = 'struct'">结构化</button>
        <button :class="{ on: tab === 'vars' }" @click="tab = 'vars'">变量与输出契约</button>
        <button :class="{ on: tab === 'run' }" @click="tab = 'run'">试运行</button>
      </div>
      <div v-if="tab === 'ver'" class="card">
        <h3>
          版本
          <small>{{ envStore.env === 'all' ? '四个环境的标签都显示' : `只突出 ${envStore.env} 的标签（${PR_LABEL[envStore.env]}）` }}；左侧点一版看内容</small>
          <span class="sp" />
          <button v-if="editable" v-write class="btn pri sm" type="button" @click="openEdit">{{ versions.length ? '编辑并保存新版本' : '写出第一版' }}</button>
        </h3>
        <div class="pgrid">
          <div class="vlist">
            <div v-if="!versions.length" class="mut">还没有版本</div>
            <div v-for="version in versions" :key="version.version" class="vi" :class="{ on: version.version === selected }" @click="pick(version.version)">
              <b>v{{ version.version }}</b>
              <span v-for="label in (version.labels ?? []).filter(showLabel)" :key="label" class="pill nd" :class="labelClass(label)">{{ label }}</span>
              <span v-if="version.source === 'code'" class="pill nd p-warn">来自代码</span>
              <span v-if="version.version === detail.versions?.[detail.versions.length - 1]?.version" class="pill nd p-mute">latest</span>
              <span v-if="detail.releases?.some((release) => release.version === version.version)" class="pill nd p-mute">发布过</span>
              <div class="m">{{ version.commitMessage }}<br />{{ version.createdBy }} · {{ formatWhen(version.createdAt) }}</div>
            </div>
          </div>
          <div>
            <div v-if="current" class="toolbar" style="margin-bottom: 10px">
              <b class="mono" style="color: var(--white)">v{{ current.version }}</b>
              <span class="mut mono" style="font-size: 11.5px">{{ shortSha(current.sha256) }}</span>
              <select class="inp" :value="compare" @change="compare = ($event.target as HTMLSelectElement).value; loadBody()">
                <option value="">不对比</option>
                <option v-for="version in versions.filter((item) => item.version !== selected)" :key="version.version" :value="String(version.version)">对比 v{{ version.version }} → v{{ selected }}</option>
              </select>
              <span class="sp" />
              <button v-for="env in targets" :key="env" v-write class="btn sm" :class="{ pri: env === 'staging' }" type="button" @click="promote(env)">在 {{ env }} 生效{{ env === 'staging' ? '（触发回归）' : '' }}</button>
              <button v-if="rollbackOk" v-write class="btn sm danger" type="button" @click="reason = ''; rolling = true">prod 回滚到此版本</button>
            </div>
            <div v-if="diff" class="diff">
              <div v-for="(line, index) in diff.lines" :key="index" :class="line.op">{{ line.op === 'add' ? '+ ' : line.op === 'del' ? '− ' : '  ' }}{{ line.text || ' ' }}</div>
            </div>
            <template v-else-if="body">
              <div v-for="(message, index) in messagesOf(body.prompt)" :key="index" class="pmsg">
                <div class="r">{{ message.role }}</div>
                <pre><template v-for="part in highlightParts(message.content)" :key="part.key"><span :class="{ pvar: part.variable }">{{ part.text }}</span></template></pre>
              </div>
            </template>
            <div v-else class="empty">还没有版本。点右上角写出第一版；保存后哪个环境都还不会生效。</div>
            <div class="kv" style="margin-top: 12px">
              <span>变量</span><b><template v-if="variables.length"><span v-for="variable in variables" :key="variable" class="chip mono">{{ braces(variable) }}</span></template><template v-else>—</template></b>
              <span>config</span><b class="mono">{{ configText }}</b>
              <span>代码里怎么用</span>
              <b class="mono" style="font-size: 11.5px">p = await ctx.prompt("{{ name }}")<br />await ctx.llm.chat(messages=p.compile({{ variables.map((item) => item + '=…').join(', ') }}), prompt=p)</b>
            </div>
          </div>
        </div>
        <div class="srcnote">版本只存在 Langfuse；Keel 库只记哪一版在 staging 生效过、回归结果和每次发布的版本号。模型参数只认 temperature、max_tokens、top_p，分段和变量 schema 写在 config.keel，模型由 manifest 决定。保存、在各环境生效、发布、回滚都会写 <code>config.change</code> 审计，只有版本号、内容哈希和改动行数，不含提示词原文。</div>
      </div>
      <div v-else-if="tab === 'struct'" class="card">
        <h3>
          v{{ selected }}
          <small v-if="previousVersion">对比 v{{ previousVersion }}</small>
          <span class="sp" />
          <button v-if="editable" v-write class="btn pri sm" type="button" @click="openStructure">编辑</button>
        </h3>
        <div v-if="removedText" class="pbanner warn">{{ removedText }}</div>
        <div v-if="!sections.length" class="empty">这一版没有分段。正文仍是编译后的 text / chat。</div>
        <div v-for="(section, index) in sections" :key="index" class="kv">
          <span>{{ SECTION_LABEL[section.kind] || section.kind }}<template v-if="section.id"> {{ section.id }}</template></span>
          <b>{{ section.text }} <span v-if="section.dimension" class="pill nd p-soft">{{ section.dimension }}</span></b>
        </div>
      </div>
      <div v-else-if="tab === 'vars'" class="card">
        <h3>变量<span class="sp" /><button v-if="editable" v-write class="btn pri sm" type="button" @click="openSchema">编辑</button></h3>
        <table class="t">
          <thead><tr><th>变量</th><th>类型</th><th>来源</th><th>必填</th><th>上限 / 截断</th><th>PII</th></tr></thead>
          <tbody>
            <tr v-for="item in schemaVariables" :key="item.name">
              <td class="mono">{{ braces(item.name) }}</td>
              <td class="mono">{{ item.type || 'string' }}</td>
              <td>{{ item.source || '—' }}</td>
              <td>{{ item.required === false ? '否' : '是' }}</td>
              <td class="mono">{{ item.limit || '—' }}</td>
              <td><span v-if="item.pii" class="pill p-warn">是</span><span v-else class="mut">否</span></td>
            </tr>
            <tr v-if="!schemaVariables.length"><td colspan="6" class="empty">没有变量 schema</td></tr>
          </tbody>
        </table>
        <h3 style="margin-top: 16px">输出契约</h3>
        <pre v-if="structure.outputContract" class="code">{{ JSON.stringify(structure.outputContract, null, 2) }}</pre>
        <p v-else class="mut">未声明</p>
        <h3 style="margin-top: 16px">检查</h3>
        <ul>
          <li v-for="(check, index) in body?.checks ?? []" :key="index">{{ check.ok ? '✓' : '·' }} {{ check.text }}</li>
          <li v-if="!(body?.checks ?? []).length" class="mut">这一版还没有检查结果</li>
        </ul>
      </div>
      <div v-else class="card">
        <h3>试编译<small>只替换变量，不调用模型</small></h3>
        <div v-for="variable in trialNames" :key="variable" class="field">
          <label class="mono">{{ braces(variable) }}</label>
          <input v-model="trialInputs[variable]" class="inp" />
        </div>
        <div v-if="samples.length" class="toolbar" style="margin-bottom: 10px">
          <button v-for="sample in samples" :key="sample.name" class="btn sm" type="button" @click="runTrial(sample.variables ?? {})">{{ sample.name }}</button>
        </div>
        <button class="btn pri sm" type="button" :disabled="selected == null" @click="runTrial({ ...trialInputs })">编译</button>
        <div v-if="trialError" class="pbanner warn" style="margin-top: 12px">{{ trialError }}</div>
        <pre v-else-if="trialText" class="code" style="margin-top: 12px">{{ trialText }}</pre>
      </div>
    </template>

    <div v-if="editing" class="mask on" @click="editing = false" />
    <div v-if="editing" class="modal wide on" role="dialog" aria-label="编辑提示词">
      <div class="mh">编辑 <span class="mono">{{ agent }}/{{ name }}</span> · 基于 v{{ selected }}</div>
      <div class="mb pedit">
        <div v-for="(draft, index) in drafts" :key="index" class="field">
          <div class="msghead">
            <select v-if="promptType === 'chat'" v-model="draft.role" class="inp">
              <option v-for="role in rolesFor(draft.role)" :key="role" :value="role">{{ role }}</option>
            </select>
            <label v-else class="mono">text</label>
            <span class="sp" />
            <button v-if="promptType === 'chat'" class="btn sm" type="button" :disabled="drafts.length < 2" @click="removeMessage(index)">删除</button>
          </div>
          <textarea v-model="draft.content" class="inp" />
        </div>
        <button v-if="promptType === 'chat'" class="btn sm" type="button" @click="addMessage">添加一条消息</button>
        <div class="cfg">
          <div class="field">
            <label>temperature</label>
            <input v-model="config.temperature" class="inp" inputmode="decimal" placeholder="0～2，可留空" />
          </div>
          <div class="field">
            <label>max_tokens</label>
            <input v-model="config.maxTokens" class="inp" inputmode="numeric" placeholder="正整数，可留空" />
          </div>
          <div class="field">
            <label>top_p</label>
            <input v-model="config.topP" class="inp" inputmode="decimal" placeholder="0～1，可留空" />
          </div>
        </div>
        <div class="field">
          <label>变量</label>
          <div>
            <template v-if="draftVariables.length"><span v-for="variable in draftVariables" :key="variable" class="chip mono">{{ braces(variable) }}</span></template>
            <span v-else class="mut">正文里还没有 <code v-pre>{{name}}</code></span>
          </div>
        </div>
        <div class="field">
          <label>提交说明（必填）</label>
          <input v-model="commitMessage" class="inp" :class="{ err: messageInvalid }" placeholder="改了什么、为什么改" />
        </div>
        <p class="mut" style="font-size: 12px">类型是 {{ promptType }}，建出版本后不能改。保存后在 Langfuse 新建一个版本，哪个环境都不会变。在版本上点“在 staging 生效”进行测试并记一笔待回归，通过评测后发布到 prod。</p>
      </div>
      <div class="mf">
        <button class="btn" type="button" @click="editing = false">取消</button>
        <button v-write class="btn pri" type="button" @click="save">保存为新版本</button>
      </div>
    </div>

    <div v-if="structuring" class="mask on" @click="structuring = false" />
    <div v-if="structuring" class="modal wide on" role="dialog" aria-label="编辑分段">
      <div class="mh">分段 · <span class="mono">{{ agent }}/{{ name }}</span></div>
      <div class="mb pedit">
        <div v-if="draftRemovedText" class="pbanner warn">{{ draftRemovedText }}</div>
        <div v-for="(section, index) in draftSections" :key="index" class="field">
          <div class="msghead">
            <select v-model="section.kind" class="inp">
              <option v-for="(label, kind) in SECTION_LABEL" :key="kind" :value="kind">{{ label }}</option>
            </select>
            <input v-if="section.kind === 'constraint'" v-model="section.dimension" class="inp" placeholder="评测维度，如 拒答 / 无法确认" />
            <span class="sp" />
            <button class="btn sm" type="button" @click="draftSections.splice(index, 1)">删除</button>
          </div>
          <textarea v-model="section.text" class="inp" />
        </div>
        <button class="btn sm" type="button" @click="addSection">添加分段</button>
        <div class="field">
          <label>提交说明（必填）</label>
          <input v-model="commitMessage" class="inp" :class="{ err: messageInvalid }" placeholder="改了什么、为什么改" />
        </div>
        <p class="mut" style="font-size: 12px">保存后按分段编译正文，并写入 config.keel。哪个环境都不会变。</p>
      </div>
      <div class="mf">
        <button class="btn" type="button" @click="structuring = false">取消</button>
        <button v-write class="btn pri" type="button" @click="saveStructure">保存为新版本</button>
      </div>
    </div>

    <div v-if="schemaEditing" class="mask on" @click="schemaEditing = false" />
    <div v-if="schemaEditing" class="modal wide on" role="dialog" aria-label="编辑变量与输出契约">
      <div class="mh">变量与输出契约 · <span class="mono">{{ agent }}/{{ name }}</span></div>
      <div class="mb pedit">
        <div v-for="(item, index) in schemaRows" :key="index" class="field">
          <div class="msghead">
            <input v-model="item.name" class="inp mono" placeholder="变量名" />
            <input v-model="item.type" class="inp" placeholder="类型" />
            <input v-model="item.source" class="inp" placeholder="来源" />
            <span class="sp" />
            <button class="btn sm" type="button" @click="schemaRows.splice(index, 1)">删除</button>
          </div>
          <div class="msghead">
            <input v-model="item.limit" class="inp" placeholder="上限 / 截断" />
            <label><input v-model="item.required" type="checkbox" /> 必填</label>
            <label><input v-model="item.pii" type="checkbox" /> PII</label>
          </div>
        </div>
        <button class="btn sm" type="button" @click="schemaRows.push({ name: '', type: 'string', required: true, pii: false })">添加变量</button>
        <div class="field">
          <label>输出契约 JSON，留空表示未声明</label>
          <textarea v-model="draftContract" class="inp" :class="{ err: contractInvalid }" />
        </div>
        <div class="field">
          <label>提交说明（必填）</label>
          <input v-model="commitMessage" class="inp" :class="{ err: messageInvalid }" />
        </div>
      </div>
      <div class="mf">
        <button class="btn" type="button" @click="schemaEditing = false">取消</button>
        <button v-write class="btn pri" type="button" @click="saveSchema">保存为新版本</button>
      </div>
    </div>

    <div v-if="rolling" class="mask on" @click="rolling = false" />
    <div v-if="rolling" class="modal on" role="dialog" aria-label="回滚提示词">
      <div class="mh">回滚 prod · <span class="mono">{{ agent }}/{{ name }}</span></div>
      <div class="mb">
        <p>把 <code>production</code> 标签挪到 <b>v{{ selected }}</b>。只能回滚到发布过的版本，不重跑门禁。线上 60 秒内生效，写发布记录和审计。staging 不受影响。</p>
        <div class="field"><label>原因</label><input v-model="reason" class="inp" placeholder="如：上线后点踩率升高" /></div>
      </div>
      <div class="mf">
        <button class="btn" type="button" @click="rolling = false">取消</button>
        <button v-write class="btn danger" type="button" @click="confirmRollback">确认回滚</button>
      </div>
    </div>
  </div>
</template>
