<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { checkAgentName, previewManifest, registerAgent, type SelfCheckReport } from '@/api/agents'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { nameError, registerBody, type WizardForm } from './wizard'

const STEPS = ['创建方式', '基本信息', '模板与资源', '预览并注册']
const TEMPLATES: [string, string, string][] = [
  ['echo', '回声', '一问一答，用来打通链路和审计'],
  ['chat-rag', '知识库问答', '客服、FAQ、文档问答'],
  ['tool-agent', '工具调用', '代码评审、CI 诊断'],
  ['graph-agent', '多步推理', '多步图'],
  ['java-spring', 'Java', 'Spring Boot + keel-starter'],
]

const router = useRouter()
const envStore = useEnvStore()
const step = ref(1)
const busy = ref(false)
const taken = ref(false)
const yaml = ref('')
const report = ref<SelfCheckReport | null>(null)
const form = reactive<WizardForm>({
  name: 'echo',
  displayName: '回声',
  ownerOrg: '研发效能组',
  ownerUser: 'amy',
  category: 'dev',
  template: 'echo',
  model: 'qwen-plus',
  dailyBudgetCny: 30,
  env: envStore.env === 'all' ? 'dev' : envStore.env,
})

const idError = computed(() => (form.name ? nameError(form.name, taken.value) : ''))
const canNext = computed(() => step.value !== 2 || !idError.value)

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

async function next() {
  if (step.value === 2) await onName()
  if (!canNext.value) return
  step.value += 1
  if (step.value === 4) await loadPreview()
}

async function submit() {
  busy.value = true
  try {
    report.value = await registerAgent(registerBody(form))
    ElMessage.success(report.value.passed ? '已注册，自检通过' : '已登记。外部开通未完成，探针已写入链路和审计')
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
      <span class="sub">登记到注册中心。回声模板会立刻写一条探针链路和审计</span>
      <span class="sp" />
      <button class="btn ghost" type="button" @click="router.push('/agents')">返回列表</button>
    </div>
    <div class="stepper">
      <div v-for="(label, index) in STEPS" :key="label" :class="{ on: step === index + 1, done: step > index + 1 }">
        <i>{{ step > index + 1 ? '✓' : index + 1 }}</i>{{ label }}
      </div>
    </div>
    <div class="wz">
      <div class="card">
        <template v-if="step === 1">
          <h3>选择创建方式</h3>
          <div class="opts">
            <div class="opt on"><b>代码开发</b><small>先用回声模板把链路和审计跑通</small></div>
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
        </template>
        <template v-else-if="step === 3">
          <h3>模板与资源</h3>
          <div class="opts">
            <div v-for="[id, title, desc] in TEMPLATES" :key="id" class="opt" :class="{ on: form.template === id }" @click="form.template = id">
              <b>{{ title }}</b><small><code>{{ id }}</code></small><small>{{ desc }}</small>
            </div>
          </div>
          <div class="row2e">
            <div class="field">
              <label>默认模型</label>
              <select v-model="form.model" class="inp">
                <option>qwen-plus</option>
                <option>deepseek-v3</option>
              </select>
            </div>
            <div class="field">
              <label>日预算（人民币）</label>
              <input v-model.number="form.dailyBudgetCny" class="inp" type="number" min="1" />
            </div>
          </div>
        </template>
        <template v-else>
          <h3>{{ report ? '注册结果' : '预览并注册' }}</h3>
          <div v-if="report" class="kv">
            <span>结果</span><b>{{ report.passed ? '自检通过' : '已登记，自检未通过' }}</b>
            <template v-for="item in report.items ?? []" :key="item.name">
              <span>{{ item.name }}</span><b>{{ item.passed ? '通过' : item.detail || '未通过' }}</b>
            </template>
          </div>
          <p v-if="report">打开链路追踪和审计中心，能看到这个智能体的探针记录。</p>
          <div class="wfoot">
            <button v-if="!report" class="btn" type="button" :disabled="busy" @click="step = 3">上一步</button>
            <button v-if="!report" v-write class="btn pri" type="button" :disabled="busy" @click="submit">{{ busy ? '注册中…' : '注册' }}</button>
            <button v-if="report" class="btn" type="button" @click="router.push('/traces')">查看链路</button>
            <button v-if="report" class="btn" type="button" @click="router.push('/audit')">查看审计</button>
            <button v-if="report" class="btn pri" type="button" @click="router.push(`/agents/${form.name}`)">查看智能体</button>
          </div>
        </template>
        <div v-if="step < 4" class="wfoot">
          <button v-if="step > 1" class="btn" type="button" @click="step -= 1">上一步</button>
          <button class="btn pri" type="button" :disabled="!canNext" @click="next">下一步</button>
        </div>
      </div>
      <div class="card">
        <h3>agent.yaml <small>回声模板的地址指向本进程里的探针</small></h3>
        <pre class="code">{{ yaml || '到最后一步会向服务端要预览' }}</pre>
      </div>
    </div>
  </div>
</template>
