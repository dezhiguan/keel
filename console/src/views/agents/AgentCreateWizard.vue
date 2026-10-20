<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { checkAgentName, getCatalog, previewManifest, registerAgent, type CatalogKnowledgeBase, type CatalogPolicy, type CatalogTool, type SelfCheckReport } from '@/api/agents'
import { getModelGateway, type ModelGateway } from '@/api/models'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { useUserStore } from '@/stores/user'
import DevflowWizard from './DevflowWizard.vue'
import { RISK } from '@/utils/format'
import { nameError, registerBody, templateForLanguage, templatesFor, type DevflowKind, type WizardForm } from './wizard'

const ACCESS = { READ: '读', WRITE: '写', EXEC: '执行' } as const

const STEPS = ['创建方式', '基本信息', '模板与资源', '预览并注册']

const route = useRoute()
const router = useRouter()
const envStore = useEnvStore()
const userStore = useUserStore()
const method = ref(route.query.method === 'devflow' ? 'devflow' : route.query.method === 'dify' ? 'dify' : 'code')
const changeAgent = typeof route.query.agent === 'string' ? route.query.agent : ''
const changeKind: DevflowKind = route.query.kind === 'CHANGE' ? 'CHANGE' : 'CREATE'
const step = ref(1)
const busy = ref(false)
const taken = ref(false)
const yaml = ref('')
const report = ref<SelfCheckReport | null>(null)
const form = reactive<WizardForm>({
  name: 'echo',
  displayName: '回声',
  ownerOrg: userStore.user?.org?.trim() || '研发效能组',
  ownerUser: userStore.user?.displayName?.trim() || '官德志',
  category: 'dev',
  language: 'python',
  endpoint: '',
  template: 'echo',
  model: 'chat-default',
  dailyBudgetCny: 30,
  env: envStore.env === 'all' ? 'dev' : envStore.env,
  tools: [],
  knowledgeBases: [],
  approvalPolicyId: '',
  promptType: 'chat',
})
const catalogTools = ref<CatalogTool[]>([])
const catalogKnowledge = ref<CatalogKnowledgeBase[]>([])
const policies = ref<CatalogPolicy[]>([])
const catalogError = ref('')
const logicalRoutes = ref<NonNullable<ModelGateway['logicalRoutes']>>([])
const modelError = ref('')

const idError = computed(() => (form.name ? nameError(form.name, taken.value) : ''))
const modelChoices = computed(() => {
  const names = [...new Set(logicalRoutes.value.map((row) => row.logical).filter((name): name is string => !!name))]
  return names.length ? names : ['chat-default', 'chat-cheap', 'long-context']
})
const visibleTemplates = computed(() => templatesFor(form.language))
const selectedHigh = computed(() => form.tools.some((name) => catalogTools.value.find((tool) => tool.name === name)?.risk === 'HIGH'))
const canNext = computed(() => {
  if (step.value === 2) return !idError.value
  if (step.value === 3 && selectedHigh.value && !form.approvalPolicyId) return false
  return true
})

async function loadLogicalRoutes() {
  try {
    const gateway = await getModelGateway({ env: form.env, range: '24h', section: 'routes' })
    logicalRoutes.value = gateway.logicalRoutes ?? []
    if (!modelChoices.value.includes(form.model)) form.model = modelChoices.value[0]
    modelError.value = ''
  } catch (error) {
    modelError.value = toKeelError(error).message
  }
}

onMounted(async () => {
  void loadLogicalRoutes()
  try {
    const catalog = await getCatalog()
    catalogTools.value = (catalog.tools ?? []).filter((tool) => tool.name)
    catalogKnowledge.value = (catalog.knowledgeBases ?? []).filter((item) => item.kb)
    policies.value = (catalog.approvalPolicies ?? []).filter((policy) => policy.id && policy.name)
  } catch (error) {
    catalogError.value = toKeelError(error).message
  }
})

watch(() => form.env, () => { void loadLogicalRoutes() })

function setLanguage(language: 'python' | 'java') {
  form.language = language
  form.template = templateForLanguage(language, form.template)
}

function toggleKnowledge(name: string) {
  const index = form.knowledgeBases.indexOf(name)
  if (index >= 0) form.knowledgeBases.splice(index, 1)
  else form.knowledgeBases.push(name)
}

function toggleTool(name: string) {
  const index = form.tools.indexOf(name)
  if (index >= 0) form.tools.splice(index, 1)
  else form.tools.push(name)
}

async function onName() {
  taken.value = false
  if (nameError(form.name)) return
  try {
    const result = await checkAgentName(form.name)
    taken.value = !result.available
  } catch {
    taken.value = false
  }
}

async function loadPreview() {
  try {
    const result = await previewManifest(registerBody(form))
    yaml.value = result.yaml
  } catch (error) {
    yaml.value = toKeelError(error).message
  }
}

let previewTimer = 0
watch(() => registerBody(form), () => {
  window.clearTimeout(previewTimer)
  previewTimer = window.setTimeout(() => { void loadPreview() }, 250)
}, { deep: true, immediate: true })

async function next() {
  if (step.value === 2) await onName()
  if (!canNext.value) return
  step.value += 1
}

async function submit() {
  busy.value = true
  try {
    report.value = await registerAgent(registerBody(form))
    if (report.value.passed) ElMessage.success('已注册，自检通过')
    else ElMessage.warning('自检未通过，已保留可恢复草稿。部署服务后重新自检')
  } catch (error) {
    ElMessage.error(`注册失败：${toKeelError(error).message}`)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <div class="vh">
      <h2>新建智能体</h2>
      <span class="sub">{{ method === 'devflow' ? '由智能体生产。填写研发需求，元智能体生成草稿和骨架 PR' : '登记到注册中心，开通资源并检查运行服务' }}</span>
      <span class="sp" />
      <button class="btn ghost" type="button" @click="router.push('/agents')">返回列表</button>
    </div>
    <div class="card method">
      <div class="field" style="margin-top: 0">
        <label>方式</label>
        <div class="radio h">
          <label :class="{ on: method === 'code' }"><input v-model="method" type="radio" value="code"><div>代码开发<small>人工编写，keel new 生成骨架（现有）</small></div></label>
          <label :class="{ on: method === 'dify' }"><input v-model="method" type="radio" value="dify"><div>低代码 Dify<small>在 Dify 配好应用后接入（现有，P3）</small></div></label>
          <label :class="{ on: method === 'devflow' }"><input v-model="method" type="radio" value="devflow"><div>由智能体生产 <span class="tag-new">新增</span><small>填写研发需求，元智能体生成草稿和骨架 PR</small></div></label>
        </div>
      </div>
    </div>
    <DevflowWizard v-if="method === 'devflow'" :agent="changeAgent" :kind="changeKind" :taken-names="[]" />
    <template v-else>
    <div class="stepper">
      <div v-for="(label, index) in STEPS" :key="label" :class="{ on: step === index + 1, done: step > index + 1 }">
        <i>{{ step > index + 1 ? '✓' : index + 1 }}</i>{{ label }}
      </div>
    </div>
    <div class="wz">
      <div class="card">
        <template v-if="step === 1">
          <h3>选择创建方式</h3>
          <p v-if="method === 'dify'" class="mut">低代码 Dify 沿用现有向导，后续三步不变。</p>
          <div class="opts">
            <div class="opt on"><b>{{ method === 'dify' ? '低代码 Dify' : '代码开发' }}</b><small>{{ method === 'dify' ? '在 Dify 配好应用后接入' : '先用回声模板把链路和审计跑通' }}</small></div>
          </div>
          <div class="field">
            <label>归类</label>
            <div class="chipsel">
              <button type="button" :class="{ on: form.category === 'biz' }" @click="form.category = 'biz'">业务智能体</button>
              <button type="button" :class="{ on: form.category === 'dev' }" @click="form.category = 'dev'">研发智能体</button>
            </div>
          </div>
        </template>
        <template v-else-if="step === 2">
          <h3>基本信息</h3>
          <div class="field">
            <label>智能体 ID</label>
            <input v-model="form.name" class="inp" :class="{ err: idError }" placeholder="echo" @change="onName" />
            <div class="hint" :class="{ err: idError }">{{ form.name ? idError || '名称可用' : '小写字母开头' }}</div>
          </div>
          <div class="field">
            <label>显示名称</label>
            <input v-model="form.displayName" class="inp" placeholder="回声" />
          </div>
          <div class="row2e">
            <div class="field">
              <label>负责组织</label>
              <input v-model="form.ownerOrg" class="inp" />
            </div>
            <div class="field">
              <label>负责人</label>
              <input v-model="form.ownerUser" class="inp" />
            </div>
          </div>
          <div v-if="method === 'code'" class="field">
            <label>语言</label>
            <div class="chipsel">
              <button type="button" :class="{ on: form.language === 'python' }" @click="setLanguage('python')">Python · keel-sdk</button>
              <button type="button" :class="{ on: form.language === 'java' }" @click="setLanguage('java')">Java · keel-starter</button>
            </div>
          </div>
        </template>
        <template v-else-if="step === 3">
          <h3>模板与资源</h3>
          <div class="opts">
            <div v-for="[id, title, desc] in visibleTemplates" :key="id" class="opt" :class="{ on: form.template === id }" @click="form.template = id">
              <b>{{ title }}</b><small><code>{{ id }}</code></small><small>{{ desc }}</small>
            </div>
          </div>
          <div class="row2e">
            <div class="field">
              <label>逻辑模型</label>
              <select v-model="form.model" class="inp">
                <option v-for="name in modelChoices" :key="name" :value="name">{{ name }}</option>
              </select>
              <div v-if="modelError" class="hint err">{{ modelError }}</div>
              <div v-else class="hint">不绑定具体模型。路由表按环境和权重选择 qwen3.8-flash 或 deepseek-flash。</div>
            </div>
            <div class="field">
              <label>日预算（人民币）</label>
              <input v-model.number="form.dailyBudgetCny" class="inp" type="number" min="1" />
            </div>
          </div>
          <div class="field">
            <label>提示词结构</label>
            <div class="chipsel">
              <button type="button" :class="{ on: form.promptType === 'chat' }" @click="form.promptType = 'chat'">对话消息</button>
              <button type="button" :class="{ on: form.promptType === 'text' }" @click="form.promptType = 'text'">纯文本</button>
            </div>
            <div class="hint">对话消息按 system / user / assistant 分行，变量写成 <code v-pre>{{question}}</code>。登记后类型不能改。第一版在提示词页保存，保存本身不切换环境。</div>
          </div>
          <div class="field">
            <label>运行服务地址</label>
            <input v-model="form.endpoint" class="inp" placeholder="http://my-agent.agents.svc:8000" />
            <div class="hint">代码开发需先部署独立服务，并注入该智能体的 Secret；登记后才能调试和统计费用。留空将使用智能体 ID 对应的集群地址。</div>
          </div>
          <div class="field">
            <label>知识库（rag-forge）</label>
            <p v-if="!catalogKnowledge.length" class="hint">rag-forge 还没有可勾选的知识库。</p>
            <div v-else class="chipsel">
              <button
                v-for="item in catalogKnowledge"
                :key="item.kb"
                type="button"
                :class="{ on: form.knowledgeBases.includes(item.kb!) }"
                @click="toggleKnowledge(item.kb!)"
              >
                {{ item.kb }}<small v-if="item.owner"> {{ item.owner }}</small>
              </button>
            </div>
            <div class="hint">勾选后写入 agent.yaml。注册时为这些库发一把只读密钥，写入这个智能体自己的 Secret。一次只能勾同一组织的库。</div>
          </div>
          <div class="field">
            <label>共享工具（需工具所有者授权）</label>
            <p v-if="catalogError" class="hint err">{{ catalogError }}</p>
            <p v-else-if="!catalogTools.length" class="hint">注册表里还没有可勾选的共享工具。</p>
            <div v-else class="chipsel">
              <button
                v-for="tool in catalogTools"
                :key="tool.name"
                type="button"
                :class="{ on: form.tools.includes(tool.name!) }"
                :title="tool.description || tool.name"
                @click="toggleTool(tool.name!)"
              >
                {{ tool.name }} · {{ ACCESS[tool.access || 'READ'] }} · {{ RISK[tool.risk || 'LOW'].label }}
              </button>
            </div>
            <div class="hint">勾选后写入 agent.yaml，并授权给这个智能体。高风险工具每次调用都会挂起，送到审批中心。</div>
          </div>
          <div v-if="selectedHigh" class="field">
            <label>高风险工具的审批策略（必填）</label>
            <select v-model="form.approvalPolicyId" class="inp">
              <option value="">请选择</option>
              <option v-for="policy in policies" :key="policy.id" :value="policy.id">{{ policy.name }}</option>
            </select>
            <div v-if="!policies.length" class="hint err">还没有工具调用的审批策略，不能勾选高风险工具。</div>
          </div>
        </template>
        <template v-else>
          <h3>{{ report ? '注册结果' : '预览并注册' }}</h3>
          <div v-if="report" class="kv">
            <span>结果</span><b>{{ report.passed ? '自检通过' : '可恢复草稿，自检未通过' }}</b>
            <template v-for="item in report.items ?? []" :key="item.name">
              <span>{{ item.name }}</span><b>{{ item.passed ? '通过' : item.detail || '未通过' }}</b>
            </template>
          </div>
          <p v-if="report">{{ report.passed ? '可以打开调试页。' : '请按失败项修复。资源开通失败可再次提交注册；服务自检失败可在部署后到详情页重新自检。通过后才开放调用。' }}</p>
          <div class="wfoot">
            <button v-if="!report" class="btn" type="button" :disabled="busy" @click="step = 3">上一步</button>
            <button v-if="!report" v-write class="btn pri" type="button" :disabled="busy" @click="submit">{{ busy ? '注册中…' : '注册' }}</button>
            <button v-if="report" class="btn" type="button" @click="router.push('/traces')">查看链路</button>
            <button v-if="report" class="btn" type="button" @click="router.push('/audit')">查看审计</button>
            <button v-if="report" class="btn" type="button" @click="router.push(`/agents/${form.name}`)">查看智能体</button>
            <button v-if="report" class="btn pri" type="button" @click="router.push(`/prompts/${form.name}/answer`)">设计提示词</button>
          </div>
        </template>
        <div v-if="step < 4" class="wfoot">
          <button v-if="step > 1" class="btn" type="button" @click="step -= 1">上一步</button>
          <button class="btn pri" type="button" :disabled="!canNext" @click="next">下一步</button>
        </div>
      </div>
      <div class="card">
        <h3>agent.yaml 实时预览<small>右侧随左侧填写变化</small></h3>
        <pre class="code">{{ yaml || '正在生成预览' }}</pre>
      </div>
    </div>
    </template>
  </div>
</template>
