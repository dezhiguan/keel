<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCatalog, listAgents, type CatalogTool } from '@/api/agents'
import { createDevflowJob } from '@/api/devflow'
import { toKeelError } from '@/api/http'
import { useUserStore } from '@/stores/user'
import {
  DEVFLOW_MODES,
  DEVFLOW_TEMPLATES,
  DEV_ROLE_TEMPLATES,
  DEV_ROLE_TOOLS,
  blankDevflow,
  devflowBody,
  devflowStepError,
  type DevflowKind,
  type DevflowMode,
} from './wizard'

const props = defineProps<{ agent?: string; kind?: DevflowKind; takenNames: string[] }>()
const router = useRouter()
const user = useUserStore()
const step = ref(0)
const busy = ref(false)
const steps = ['基本信息', '需求细节', '交付范围', '预览并提交']
const form = reactive(blankDevflow(props.agent ?? '', props.kind ?? 'CREATE'))
watch(() => form.agent, (name) => {
  form.template = DEV_ROLE_TEMPLATES[name] || (form.template && form.template !== 'java-spring' ? form.template : 'tool-agent')
  form.tools = [...(DEV_ROLE_TOOLS[name] || [])]
})
const catalogTools = ref<CatalogTool[]>([])
const catalogAgents = ref<string[]>([])
const catalogError = ref('')
onMounted(async () => {
  try { catalogTools.value = (await getCatalog()).tools ?? [] }
  catch (error) { catalogError.value = toKeelError(error).message }
  try { catalogAgents.value = ((await listAgents({ page: 1, size: 100 })).items ?? [])
    .filter(item => item.name && ['REGISTERED', 'ONLINE'].includes(item.status || ''))
    .map(item => item.name!) }
  catch (error) { catalogError.value = toKeelError(error).message }
})
const admin = computed(() => user.user?.platformRole === 'ADMIN')
const modes = Object.entries(DEVFLOW_MODES) as [DevflowMode, [string, string]][]

function pickLayer(layer: 'biz' | 'dev') {
  if (layer !== 'dev' || !admin.value) return
  form.layer = layer
  if (layer === 'dev') form.mode = 'COLLAB'
}

function templateOpen(id: string) {
  return id !== 'java-spring' && (!DEV_ROLE_TEMPLATES[form.agent] || id === DEV_ROLE_TEMPLATES[form.agent])
}

function selectTemplate(id: string) {
  if (!templateOpen(id)) return
  form.template = id
  if (id !== 'supervisor') form.delegates = []
}

function next() {
  const error = devflowStepError(step.value, form, props.takenNames)
  if (error) {
    ElMessage.warning(error)
    return
  }
  step.value += 1
}

async function submit() {
  if (!admin.value) { ElMessage.warning('仅平台管理员可创建研发智能体'); return }
  const error = devflowStepError(0, form, props.takenNames) || devflowStepError(1, form)
  if (error) { ElMessage.warning(error); return }
  busy.value = true
  try {
    const job = await createDevflowJob(devflowBody(form, user.user?.org || '研发效能组'))
    ElMessage.success(`已创建研发任务 ${job.jobId}`)
    router.push(`/jobs/${job.jobId}`)
  } catch (error) {
    ElMessage.error(`提交失败：${toKeelError(error).message}`)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <div class="stepper">
      <div v-for="(label, index) in steps" :key="label" :class="{ on: step === index, done: step > index }">
        <i>{{ step > index ? '✓' : index + 1 }}</i>{{ label }}
      </div>
    </div>
    <div class="card">
      <template v-if="step === 0">
        <div class="grid2">
          <div class="field">
            <label>分类</label>
            <div class="radio">
              <label class="dis"><input type="radio" disabled><div>业务智能体<small>业务流水线尚未开放</small></div></label>
              <label :class="{ on: form.layer === 'dev', dis: !admin }"><input type="radio" :checked="form.layer === 'dev'" :disabled="!admin" @change="pickLayer('dev')"><div>研发智能体<small>由 meta-agent 生产，强制人机协作，关口由平台管理员把关{{ admin ? '' : ' · 仅平台管理员' }}</small></div></label>
            </div>
          </div>
          <div class="field">
            <label>任务类型</label>
            <div class="radio">
              <label :class="{ on: form.kind === 'CREATE' }"><input v-model="form.kind" type="radio" value="CREATE"><div>新建</div></label>
              <label :class="{ on: form.kind === 'CHANGE' }"><input type="radio" value="CHANGE" disabled><div>改造已有智能体<small>改造流程尚未开放</small></div></label>
            </div>
          </div>
        </div>
        <div class="field">
          <label>开发模式</label>
          <div class="radio h">
            <label v-for="[key, [title, desc]] in modes" :key="key" :class="{ on: form.mode === key, dis: key !== 'COLLAB' }">
              <input v-model="form.mode" type="radio" :value="key" :disabled="key !== 'COLLAB'">
              <div>{{ title }}<small>{{ desc }}</small></div>
            </label>
          </div>
          <div class="hint">元智能体按确认的需求生成独立项目和岗位代码候选；沙箱、评测、授权、人工评审和发布必须逐项通过。</div>
        </div>
        <div class="grid2">
          <div class="field"><label>标题<b>*</b></label><input v-model="form.title" class="inp"></div>
          <div class="field"><label>智能体 ID<b>*</b></label><input v-model="form.agent" class="inp mono"><div class="hint">小写字母、数字和连字符，生成后作为独立项目名称</div></div>
        </div>
        <div class="field"><label>一句话目标<b>*</b></label><textarea v-model="form.goal" class="inp" /></div>
        <div class="field">
          <label>模板</label>
          <div class="tplgrid">
            <div v-for="[id, desc] in DEVFLOW_TEMPLATES" :key="id" class="tpl" :class="{ on: form.template === id, dis: !templateOpen(id) }" @click="selectTemplate(id)">
              <b>{{ id }}</b><small>{{ desc }}{{ DEV_ROLE_TEMPLATES[form.agent] ? (templateOpen(id) ? ' · 原型指定' : ' · 不适用于当前岗位') : (templateOpen(id) ? '' : ' · 暂未开放') }}</small>
            </div>
          </div>
        </div>
      </template>
      <template v-else-if="step === 1">
        <div class="grid2">
          <div class="field"><label>谁用、在什么场景<b>*</b></label><textarea v-model="form.users" class="inp" /></div>
          <div class="field"><label>输入与输出示例<b>*</b></label><textarea v-model="form.io" class="inp" /></div>
        </div>
        <div class="field"><label>怎样算做得好<b>*</b></label><textarea v-model="form.success" class="inp" /></div>
        <div class="grid2">
          <div class="field">
            <label>共享工具（授权须在 H3 办理）</label>
            <div class="chipsel">
              <button v-for="tool in catalogTools" :key="tool.name" type="button" :class="{ on: form.tools.includes(tool.name || '') }" :disabled="!!DEV_ROLE_TEMPLATES[form.agent]" @click="tool.name && (form.tools.includes(tool.name) ? form.tools.splice(form.tools.indexOf(tool.name), 1) : form.tools.push(tool.name))">{{ tool.name }} · {{ tool.risk?.toLowerCase() }}</button>
            </div>
            <div v-if="form.tools.some(name => !catalogTools.some(item => item.name === name)) && !catalogError" class="hint">目录缺少岗位必需工具，当前任务无法提交。</div>
          </div>
          <div class="field">
            <label>知识库</label>
            <div class="hint">当前创建入口尚未接入知识库授权；需要知识库的岗位应在 H3 前暂停。</div>
          </div>
        </div>
        <div v-if="form.template === 'supervisor' && !DEV_ROLE_TEMPLATES[form.agent]" class="field">
          <label>可委派的已登记智能体<b>*</b></label>
          <div class="chipsel">
            <button v-for="name in catalogAgents.filter(name => name !== form.agent && name !== 'meta-agent')" :key="name" type="button" :class="{ on: form.delegates.includes(name) }" :disabled="!form.delegates.includes(name) && form.delegates.length >= 5" @click="form.delegates.includes(name) ? form.delegates.splice(form.delegates.indexOf(name), 1) : form.delegates.push(name)">{{ name }}</button>
          </div>
          <div class="hint">最多选 5 个。平台只允许委派清单里的智能体，生成项目会在 agent.yaml 声明它们。</div>
        </div>
        <div v-if="catalogError" class="banner warn">工具目录加载失败：{{ catalogError }}</div>
        <div class="grid3">
          <div class="field"><label>日预算（¥）</label><input v-model.number="form.dailyBudgetCny" class="inp mono" type="number" min="1"></div>
          <div class="field"><label>任务预算（¥）</label><input class="inp mono" value="120" disabled></div>
          <div class="field"><label>负责人</label><input class="inp" :value="user.user?.displayName || '平台管理员'" disabled></div>
        </div>
      </template>
      <template v-else-if="step === 2">
        <div class="banner info">确认需求后，元智能体会按岗位模板生成实际代码与候选 PR。候选代码不代表通过测试或上岗。</div>
        <div class="hint">随后必须完成人工种子用例与 H2 确认、H3 授权、隔离沙箱、人工 PR 评审、staging 门禁、H4 审批及 CI 发布。未接通的关口会保持阻塞。</div>
      </template>
      <template v-else>
        <div class="kv">
          <span>分类</span><b>{{ form.layer === 'dev' ? '研发 · 生产者 meta-agent' : '业务 · 生产者 dev-lead' }}</b>
          <span>开发模式</span><b>{{ DEVFLOW_MODES[form.mode][0] }}</b>
          <span>标题</span><b>{{ form.title }}</b>
          <span>智能体 ID</span><b class="mono">{{ form.agent }}</b>
          <span>工具</span><b>{{ form.tools.join('、') || '—' }}</b>
          <span>子智能体</span><b>{{ form.delegates.join('、') || '—' }}</b>
          <span>种子用例</span><b class="mono">{{ form.seedCount || '稍后上传' }}</b>
        </div>
        <div class="banner warn">提交后元智能体生成需求草稿。H1 确认后生成岗位代码候选 PR；完成原型全部关口前，不会标记为已上岗。</div>
      </template>
      <div class="wfoot">
        <button v-if="step" class="btn" type="button" @click="step -= 1">上一步</button>
        <button v-if="step < 3" class="btn pri" type="button" @click="next">下一步</button>
        <button v-else v-write class="btn pri" type="button" :disabled="busy || !admin || !!catalogError || form.tools.some(name => !catalogTools.some(item => item.name === name))" @click="submit">{{ busy ? '提交中…' : '提交' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.upload { width: 100%; border: 1px dashed var(--line2); border-radius: 10px; padding: 22px; text-align: center; background: transparent; color: inherit; cursor: pointer; }
</style>
