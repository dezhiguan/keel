<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCatalog, type CatalogTool } from '@/api/agents'
import { createDevflowJob } from '@/api/devflow'
import { toKeelError } from '@/api/http'
import { useUserStore } from '@/stores/user'
import {
  DEVFLOW_MODES,
  DEVFLOW_TEMPLATES,
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
const catalogTools = ref<CatalogTool[]>([])
const catalogError = ref('')
onMounted(async () => {
  try { catalogTools.value = (await getCatalog()).tools ?? [] }
  catch (error) { catalogError.value = toKeelError(error).message }
})
const admin = computed(() => user.user?.platformRole === 'ADMIN')
const modes = Object.entries(DEVFLOW_MODES) as [DevflowMode, [string, string]][]

function pickLayer(layer: 'biz' | 'dev') {
  if (layer !== 'dev' || !admin.value) return
  form.layer = layer
  if (layer === 'dev') form.mode = 'COLLAB'
}

function toggle(list: string[], value: string) {
  const index = list.indexOf(value)
  if (index >= 0) list.splice(index, 1)
  else list.push(value)
}

function templateOpen(id: string) {
  return id === 'tool-agent'
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
          <div class="hint">本次交付需求单、agent.yaml 和 Python 骨架 PR，随后由人继续开发。评测、部署和发布尚未接入。</div>
        </div>
        <div class="grid2">
          <div class="field"><label>标题<b>*</b></label><input v-model="form.title" class="inp"></div>
          <div class="field"><label>智能体 ID<b>*</b></label><input v-model="form.agent" class="inp mono"><div class="hint">小写字母开头，3～40 位</div></div>
        </div>
        <div class="field"><label>一句话目标<b>*</b></label><textarea v-model="form.goal" class="inp" /></div>
        <div class="field">
          <label>模板</label>
          <div class="tplgrid">
            <div v-for="[id, desc] in DEVFLOW_TEMPLATES" :key="id" class="tpl" :class="{ on: form.template === id, dis: !templateOpen(id) }" @click="templateOpen(id) && (form.template = id)">
              <b>{{ id }}</b><small>{{ desc }}{{ templateOpen(id) ? '' : ' · 未开放' }}</small>
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
            <label>共享工具（取自当前工具目录，授权在开发完成后办理）</label>
            <div class="chipsel">
              <button v-for="tool in catalogTools" :key="tool.name" type="button" :class="{ on: form.tools.includes(tool.name!) }" @click="toggle(form.tools, tool.name!)">{{ tool.name }} · {{ tool.risk?.toLowerCase() }}</button>
            </div>
          </div>
          <div class="field">
            <label>知识库</label>
            <div class="hint">骨架交付暂不支持知识库配置。</div>
          </div>
        </div>
        <div v-if="catalogError" class="banner warn">工具目录加载失败：{{ catalogError }}</div>
        <div class="grid3">
          <div class="field"><label>日预算（¥）</label><input v-model.number="form.dailyBudgetCny" class="inp mono" type="number" min="1"></div>
          <div class="field"><label>任务预算（¥）</label><input class="inp mono" value="120" disabled></div>
          <div class="field"><label>负责人</label><input class="inp" :value="user.user?.displayName || '平台管理员'" disabled></div>
        </div>
      </template>
      <template v-else-if="step === 2">
        <div class="banner info">确认需求后，元智能体将生成 Python tool-agent 骨架并开 PR，停在人工开发阶段。</div>
        <div class="hint">此阶段不上传评测用例，不生成隐藏考题，也不自动部署。交付内容包括需求单、agent.yaml、项目模板和真实 Git 提交。</div>
      </template>
      <template v-else>
        <div class="kv">
          <span>分类</span><b>{{ form.layer === 'dev' ? '研发 · 生产者 meta-agent' : '业务 · 生产者 dev-lead' }}</b>
          <span>开发模式</span><b>{{ DEVFLOW_MODES[form.mode][0] }}</b>
          <span>标题</span><b>{{ form.title }}</b>
          <span>智能体 ID</span><b class="mono">{{ form.agent }}</b>
          <span>工具</span><b>{{ form.tools.join('、') || '—' }}</b>
          <span>种子用例</span><b class="mono">{{ form.seedCount || '稍后上传' }}</b>
        </div>
        <div class="banner warn">提交后元智能体会实际生成草稿，需求确认待办将出现在审批中心。确认前可修改或退回；确认后生成骨架 PR。</div>
      </template>
      <div class="wfoot">
        <button v-if="step" class="btn" type="button" @click="step -= 1">上一步</button>
        <button v-if="step < 3" class="btn pri" type="button" @click="next">下一步</button>
        <button v-else v-write class="btn pri" type="button" :disabled="busy || !admin" @click="submit">{{ busy ? '提交中…' : '提交' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.upload { width: 100%; border: 1px dashed var(--line2); border-radius: 10px; padding: 22px; text-align: center; background: transparent; color: inherit; cursor: pointer; }
</style>
