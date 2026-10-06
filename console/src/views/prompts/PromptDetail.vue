<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { diffPrompt, getPrompt, getPromptVersion, promotePrompt, rollbackPrompt, savePromptVersion, type PromptBody, type PromptDiff } from '@/api/prompts'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import {
  banners, canEdit, canRollback, formatWhen, highlightParts, PR_ENVS, PR_LABEL, promotableEnvs, shortSha, variablesOf,
  type EnvName, type PromptDetailModel,
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
const commitMessage = ref('')
const reason = ref('')
const drafts = ref<{ role: string; content: string }[]>([])
const messageInvalid = ref(false)

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
const configText = computed(() => Object.entries(detail.value?.config ?? {}).map(([key, value]) => `${key}=${value}`).join(' · ') || '—')
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
  } catch (error) {
    ElMessage.error(`读取版本失败：${toKeelError(error).message}`)
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
  if (!body.value) return
  drafts.value = messagesOf(body.value.prompt).map((message) => ({ ...message }))
  commitMessage.value = ''
  messageInvalid.value = false
  editing.value = true
}

async function save() {
  if (!commitMessage.value.trim()) {
    messageInvalid.value = true
    ElMessage.error('请填写提交说明')
    return
  }
  const prompt = detail.value?.type === 'chat'
    ? drafts.value.map((draft) => ({ role: draft.role, content: draft.content }))
    : drafts.value[0]?.content ?? ''
  try {
    const saved = await savePromptVersion(agent.value, name.value, {
      prompt,
      commitMessage: commitMessage.value.trim(),
      config: detail.value?.config,
    })
    editing.value = false
    ElMessage.success(`已保存为 v${saved.version}，哪个环境都还没切过去`)
    await load(saved.version)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  }
}

async function promote(env: EnvName) {
  if (selected.value == null || env === 'prod') return
  try {
    await promotePrompt(agent.value, name.value, env, selected.value)
    ElMessage.info(env === 'staging'
      ? `staging 标签已挪到 v${selected.value}，60 秒内生效；请手动运行 keel gate`
      : `${env} 标签已挪到 v${selected.value}，60 秒内生效；不跑门禁`)
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

watch(() => [agent.value, name.value], () => load(), { immediate: true })
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
        <button v-if="banner.action?.kind === 'rollback'" class="btn sm" type="button" @click="pick(banner.action.version); rolling = true">{{ banner.action.label }}</button>
        <button v-else-if="banner.action?.kind === 'pick'" class="btn sm" type="button" @click="pick(banner.action.version)">{{ banner.action.label }}</button>
      </div>

      <div class="card">
        <h3>
          版本
          <small>{{ envStore.env === 'all' ? '四个环境的标签都显示' : `只突出 ${envStore.env} 的标签（${PR_LABEL[envStore.env]}）` }}；左侧点一版看内容</small>
          <span class="sp" />
          <button v-if="editable" class="btn pri sm" type="button" @click="openEdit">编辑并保存新版本</button>
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
              <button v-for="env in targets" :key="env" class="btn sm" :class="{ pri: env === 'staging' }" type="button" @click="promote(env)">在 {{ env }} 生效{{ env === 'staging' ? '（触发回归）' : '' }}</button>
              <button v-if="rollbackOk" class="btn sm danger" type="button" @click="reason = ''; rolling = true">prod 回滚到此版本</button>
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
            <div v-else class="empty">没有版本</div>
            <div class="kv" style="margin-top: 12px">
              <span>变量</span><b><template v-if="variables.length"><span v-for="variable in variables" :key="variable" class="chip mono">{{ braces(variable) }}</span></template><template v-else>—</template></b>
              <span>config</span><b class="mono">{{ configText }}</b>
              <span>代码里怎么用</span>
              <b class="mono" style="font-size: 11.5px">p = await ctx.prompt("{{ name }}")<br />await ctx.llm.chat(messages=p.compile({{ variables.map((item) => item + '=…').join(', ') }}), prompt=p)</b>
            </div>
          </div>
        </div>
        <div class="srcnote">版本只存在 Langfuse；Keel 库只记哪一版在 staging 生效过、回归结果和每次发布的版本号。config 只认 temperature、max_tokens、top_p，模型由 manifest 决定。保存、在各环境生效、发布、回滚都会写 <code>config.change</code> 审计，只有版本号、内容哈希和改动行数，不含提示词原文。</div>
      </div>
    </template>

    <div v-if="editing" class="mask on" @click="editing = false" />
    <div v-if="editing" class="modal wide on" role="dialog" aria-label="编辑提示词">
      <div class="mh">编辑 <span class="mono">{{ agent }}/{{ name }}</span> · 基于 v{{ selected }}</div>
      <div class="mb pedit">
        <div v-for="(draft, index) in drafts" :key="index" class="field">
          <label class="mono">{{ draft.role }}</label>
          <textarea v-model="draft.content" class="inp" />
        </div>
        <div class="field">
          <label>提交说明（必填）</label>
          <input v-model="commitMessage" class="inp" :class="{ err: messageInvalid }" placeholder="改了什么、为什么改" />
        </div>
        <p class="mut" style="font-size: 12px">保存后在 Langfuse 新建一个版本，哪个环境都不会变。在版本上点“在 dev / test 生效”先试，“在 staging 生效”会记一笔待回归。变量用 <code v-pre>{{name}}</code>。</p>
      </div>
      <div class="mf">
        <button class="btn" type="button" @click="editing = false">取消</button>
        <button class="btn pri" type="button" @click="save">保存为新版本</button>
      </div>
    </div>

    <div v-if="rolling" class="mask on" @click="rolling = false" />
    <div v-if="rolling" class="modal on" role="dialog" aria-label="回滚提示词">
      <div class="mh">回滚 prod · <span class="mono">{{ agent }}/{{ name }}</span></div>
      <div class="mb">
        <p>把 <code>production</code> 标签挪到 <b>v{{ selected }}</b>。只能回滚到发布过的版本，不重跑门禁。线上 60 秒内生效，写发布记录和审计。dev、test、staging 不受影响。</p>
        <div class="field"><label>原因</label><input v-model="reason" class="inp" placeholder="如：上线后点踩率升高" /></div>
      </div>
      <div class="mf">
        <button class="btn" type="button" @click="rolling = false">取消</button>
        <button class="btn danger" type="button" @click="confirmRollback">确认回滚</button>
      </div>
    </div>
  </div>
</template>
