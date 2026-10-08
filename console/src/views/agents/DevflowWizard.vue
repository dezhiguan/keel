<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createDevflowJob } from '@/api/devflow'
import { toKeelError } from '@/api/http'
import { useUserStore } from '@/stores/user'
import {
  DEVFLOW_KBS,
  DEVFLOW_MODES,
  DEVFLOW_TEMPLATES,
  DEVFLOW_TOOLS,
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
const steps = ['基本信息', '需求细节', '种子用例', '预览并提交']
const form = reactive(blankDevflow(props.agent ?? '', props.kind ?? 'CREATE'))
const admin = computed(() => user.user?.platformRole === 'ADMIN')
const modes = Object.entries(DEVFLOW_MODES) as [DevflowMode, [string, string]][]

function pickLayer(layer: 'biz' | 'dev') {
  if (layer === 'dev' && !admin.value) return
  form.layer = layer
  if (layer === 'dev') form.mode = 'COLLAB'
}

function toggle(list: string[], value: string) {
  const index = list.indexOf(value)
  if (index >= 0) list.splice(index, 1)
  else list.push(value)
}

function templateOpen(id: string) {
  return form.layer === 'dev' || id === 'tool-agent'
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
              <label :class="{ on: form.layer === 'biz' }"><input type="radio" :checked="form.layer === 'biz'" @change="pickLayer('biz')"><div>业务智能体<small>由研发流水线（dev-lead）生产，支持批量</small></div></label>
              <label :class="{ on: form.layer === 'dev', dis: !admin }"><input type="radio" :checked="form.layer === 'dev'" :disabled="!admin" @change="pickLayer('dev')"><div>研发智能体<small>由 meta-agent 生产，强制人机协作，关口由平台管理员把关{{ admin ? '' : ' · 仅平台管理员' }}</small></div></label>
            </div>
          </div>
          <div class="field">
            <label>任务类型</label>
            <div class="radio">
              <label :class="{ on: form.kind === 'CREATE' }"><input v-model="form.kind" type="radio" value="CREATE"><div>新建</div></label>
              <label :class="{ on: form.kind === 'CHANGE' }"><input v-model="form.kind" type="radio" value="CHANGE"><div>改造已有智能体<small>不能改自己和上级</small></div></label>
            </div>
          </div>
        </div>
        <div class="field">
          <label>开发模式</label>
          <div class="radio h">
            <label v-for="[key, [title, desc]] in modes" :key="key" :class="{ on: form.mode === key, dis: form.layer === 'dev' && key !== 'COLLAB' }">
              <input v-model="form.mode" type="radio" :value="key" :disabled="form.layer === 'dev' && key !== 'COLLAB'">
              <div>{{ title }}<small>{{ desc }}</small></div>
            </label>
          </div>
          <div class="hint">任何模式下都能随时人工接管。产出只进 staging，上生产要过发布审批。</div>
        </div>
        <div class="grid2">
          <div class="field"><label>标题 *</label><input v-model="form.title" class="inp"></div>
          <div class="field"><label>智能体 ID *</label><input v-model="form.agent" class="inp mono"><div class="hint">小写字母开头，3～40 位</div></div>
        </div>
        <div class="field"><label>一句话目标 *</label><textarea v-model="form.goal" class="inp" /></div>
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
          <div class="field"><label>谁用、在什么场景 *</label><textarea v-model="form.users" class="inp" /></div>
          <div class="field"><label>输入与输出示例 *</label><textarea v-model="form.io" class="inp" /></div>
        </div>
        <div class="field"><label>怎样算做得好 *</label><textarea v-model="form.success" class="inp" /></div>
        <div class="grid2">
          <div class="field">
            <label>共享工具（取自工具页注册表）</label>
            <div class="chipsel">
              <button v-for="tool in DEVFLOW_TOOLS" :key="tool" type="button" :class="{ on: form.tools.includes(tool) }" @click="toggle(form.tools, tool)">{{ tool }}</button>
            </div>
          </div>
          <div class="field">
            <label>知识库</label>
            <div class="chipsel">
              <button v-for="kb in DEVFLOW_KBS" :key="kb" type="button" :class="{ on: form.knowledge.includes(kb) }" @click="toggle(form.knowledge, kb)">{{ kb }}</button>
            </div>
          </div>
        </div>
        <div class="grid3">
          <div class="field"><label>日预算（¥）</label><input v-model.number="form.dailyBudgetCny" class="inp mono" type="number" min="1"></div>
          <div class="field"><label>任务预算（¥）</label><input class="inp mono" value="80" disabled></div>
          <div class="field"><label>负责人</label><input class="inp" :value="user.user?.displayName || '平台管理员'" disabled></div>
        </div>
      </template>
      <template v-else-if="step === 2">
        <div class="banner info">种子用例必须由人提供。确认后服务端随机切出 30% 作为隐藏考题。</div>
        <button class="upload" type="button" @click="form.seedCount = form.seedCount || 34">
          <template v-if="form.seedCount">seed.jsonl · <span class="mono">{{ form.seedCount }}</span> 条</template>
          <span v-else class="mut">点击模拟上传（也可在评测确认时再传）</span>
        </button>
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
        <div class="banner warn">提交后会在研发任务里生成一条任务。需要处理时出现在审批中心。委托授权尚未接入，这一步先记下需求。</div>
      </template>
      <div class="wfoot">
        <button v-if="step" class="btn" type="button" @click="step -= 1">上一步</button>
        <button v-if="step < 3" class="btn pri" type="button" @click="next">下一步</button>
        <button v-else v-write class="btn pri" type="button" :disabled="busy" @click="submit">{{ busy ? '提交中…' : '提交' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.upload { width: 100%; border: 1px dashed var(--line2); border-radius: 10px; padding: 22px; text-align: center; background: transparent; color: inherit; cursor: pointer; }
</style>
