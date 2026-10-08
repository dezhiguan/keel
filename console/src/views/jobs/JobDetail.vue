<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  assistDevflowJob,
  cancelDevflowJob,
  getDevflowJob,
  getDevflowSettings,
  handbackDevflowJob,
  takeoverDevflowJob,
  type DevflowJob,
} from '@/api/devflow'
import { toKeelError } from '@/api/http'
import JobStatus from './JobStatus.vue'
import {
  DETAIL_POLL_MS,
  actorColor,
  artifacts,
  budgetHot,
  budgetWidth,
  canAssist,
  canCancel,
  canHandback,
  canTakeover,
  cny,
  detailNotices,
  handbackLabel,
  isLive,
  kindLabel,
  layerClass,
  layerShort,
  manifestYaml,
  repoLabel,
  seedTotal,
  showApprovalLink,
  timeline,
  visibleSeed,
} from './jobs'

const route = useRoute()
const job = ref<DevflowJob | null>(null)
const loading = ref(false)
const missing = ref(false)
const busy = ref(false)
const tab = ref<'progress' | 'artifacts' | 'links'>('progress')
const modal = ref<'takeover' | 'assist' | 'cancel' | null>(null)
const instruction = ref('')
const manifestOpen = ref(false)
const concurrency = ref(3)

const jobId = computed(() => String(route.params.jobId ?? ''))
const notices = computed(() => (job.value ? detailNotices(job.value, concurrency.value) : []))
const rows = computed(() => (job.value ? timeline(job.value) : []))
const arts = computed(() => (job.value ? artifacts(job.value) : []))
const yaml = computed(() => (job.value ? manifestYaml(job.value) : ''))

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    job.value = await getDevflowJob(jobId.value)
    missing.value = false
    if (job.value.status === 'QUEUED') {
      concurrency.value = (await getDevflowSettings()).concurrency
    }
  } catch (error) {
    const keel = toKeelError(error)
    if (keel.code === 'SERVER_NOT_FOUND') {
      missing.value = true
      job.value = null
    } else if (!silent) {
      ElMessage.error(`加载任务失败：${keel.message}`)
    }
  } finally {
    loading.value = false
  }
}

async function run(action: () => Promise<DevflowJob>, done: string) {
  busy.value = true
  try {
    job.value = await action()
    modal.value = null
    instruction.value = ''
    ElMessage.success(done)
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    busy.value = false
  }
}

function confirmAssist() {
  if (!job.value) return
  run(() => assistDevflowJob(job.value!.jobId, instruction.value), '已发给 dev-agent')
}

function openArtifact(kind: string) {
  if (kind === 'MANIFEST') {
    manifestOpen.value = true
    return
  }
  ElMessage.info('产物详情尚未接入，稍后从 Git 或评测中心打开')
}

function onVisible() {
  if (document.visibilityState === 'visible') load(true)
}

let timer = 0
function arm() {
  window.clearInterval(timer)
  if (!job.value || !isLive(job.value.status)) return
  timer = window.setInterval(() => {
    if (document.visibilityState !== 'hidden') load(true)
  }, DETAIL_POLL_MS)
}

watch(jobId, () => {
  tab.value = 'progress'
  modal.value = null
  manifestOpen.value = false
  load()
})
watch(() => job.value?.status, arm)

onMounted(() => {
  document.addEventListener('visibilitychange', onVisible)
  load()
})
onUnmounted(() => {
  window.clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisible)
})
</script>

<template>
  <div v-loading="loading">
    <div v-if="missing" class="empty">任务不存在</div>
    <template v-else-if="job">
      <div class="vh">
        <span class="ly" :class="layerClass(job.layer)">{{ layerShort(job.layer) }}</span>
        <h2>{{ job.title }}</h2>
        <span class="mono mut">{{ job.jobId }}</span>
        <JobStatus :job="job" />
        <span class="sp" />
        <RouterLink v-if="showApprovalLink(job)" class="btn pri" to="/approvals">去审批中心处理</RouterLink>
        <button v-if="canTakeover(job)" v-write class="btn vio" type="button" @click="modal = 'takeover'">人工接管开发</button>
        <template v-if="canHandback(job)">
          <button v-if="canAssist(job)" v-write class="btn vio" type="button" @click="modal = 'assist'">请智能体帮忙</button>
          <button v-write class="btn pri" type="button" :disabled="busy" @click="run(() => handbackDevflowJob(job!.jobId), job.mode === 'SCAFFOLD' ? '已提交门禁' : '已交还')">{{ handbackLabel(job.mode) }}</button>
        </template>
        <button v-if="canCancel(job)" v-write class="btn danger" type="button" @click="modal = 'cancel'">取消</button>
      </div>

      <div v-for="(note, index) in notices" :key="index" class="banner" :class="note.tone">
        <b v-if="note.title" :class="note.tone === 'vio' ? 'vioc' : ''">{{ note.title }}</b>
        <span>{{ note.text }}</span>
      </div>

      <div class="kpis">
        <div class="kpi">
          <div class="l">产出智能体</div>
          <div class="v sm">{{ job.targetAgent }}</div>
          <div class="d">{{ kindLabel(job.kind) }} · {{ job.template }}</div>
        </div>
        <div class="kpi">
          <div class="l">生产者 · 开发模式</div>
          <div class="v sm">{{ job.producerAgent }}</div>
          <div class="d">{{ job.mode === 'AUTO' ? '全托管' : job.mode === 'COLLAB' ? '人机协作' : '只生成骨架' }}</div>
        </div>
        <div class="kpi">
          <div class="l">花费 / 预算</div>
          <div class="v">{{ cny(job.spentCny) }}</div>
          <div class="bar" style="margin-top: 6px"><i :style="{ width: `${budgetWidth(job.spentCny, job.budgetCny)}%`, background: budgetHot(job.spentCny, job.budgetCny) ? 'var(--bad)' : 'var(--teal)' }" /></div>
        </div>
        <div class="kpi">
          <div class="l">修复轮次</div>
          <div class="v" :class="{ badc: job.fixRounds >= job.maxFixRounds }">{{ job.fixRounds }}/{{ job.maxFixRounds }}</div>
          <div class="d">{{ job.status === 'HUMAN' ? '人工开发中暂停计数' : '相同失败两次直接失败' }}</div>
        </div>
        <div class="kpi">
          <div class="l">评测用例</div>
          <div class="v">{{ seedTotal(job.seed) || '—' }}</div>
          <div class="d">人 {{ job.seed.human }} · 智能体 {{ job.seed.agent }} · 隐藏 {{ job.seed.holdout }}</div>
        </div>
      </div>

      <div class="dtabs">
        <button type="button" :class="{ on: tab === 'progress' }" @click="tab = 'progress'">进度</button>
        <button type="button" :class="{ on: tab === 'artifacts' }" @click="tab = 'artifacts'">产物</button>
        <button type="button" :class="{ on: tab === 'links' }" @click="tab = 'links'">评测 · 链路 · 成本</button>
      </div>

      <template v-if="tab === 'progress'">
        <div class="row2">
          <div class="card">
            <h3>阶段时间线</h3>
            <div class="tl">
              <div v-for="row in rows" :key="row.key" class="it">
                <div class="dot" :class="[row.mark, { h: row.human }]">{{ row.glyph }}</div>
                <div class="nm2"><b>{{ row.title }}</b><small>{{ row.detail }}</small></div>
                <div class="rt">{{ row.result }}</div>
              </div>
            </div>
          </div>
          <div class="card">
            <h3>动态 <small>只记结论和产物引用</small></h3>
            <ul class="log">
              <li v-if="!job.events.length"><span class="tm">—</span><span class="who">—</span><span>暂无</span></li>
              <li v-for="(event, index) in [...job.events].reverse()" :key="index">
                <span class="tm">{{ event.at }}</span>
                <span class="who" :style="{ color: actorColor(event.actor) }">{{ event.actor }}</span>
                <span>{{ event.summary }}</span>
              </li>
            </ul>
          </div>
        </div>
        <div class="card">
          <h3>需求</h3>
          <div class="kv">
            <span>目标</span><span>{{ job.goal }}</span>
            <span>工具</span>
            <span>
              <template v-if="job.tools.length">
                <span v-for="tool in job.tools" :key="tool.name" class="chip">{{ tool.name }} · <b>{{ tool.risk.toLowerCase() }}</b></span>
              </template>
              <template v-else>—</template>
            </span>
            <span>仓库</span><span class="mono">{{ repoLabel(job) }}</span>
            <span>负责人</span><span>{{ job.requester }}（{{ job.ownerOrg }}）</span>
          </div>
        </div>
      </template>

      <div v-else-if="tab === 'artifacts'" class="card">
        <h3>产物 <small>只存引用（git sha、数据集名、报告 id）</small></h3>
        <table class="t">
          <tbody>
            <tr v-for="item in arts" :key="item.kind">
              <td><span class="pill nd p-soft">{{ item.kind }}</span></td>
              <td>{{ item.label }}</td>
              <td><span v-if="item.ready">{{ item.origin }}</span><span v-else class="mut">未产出</span></td>
              <td>
                <button v-if="item.ready" class="btn sm" type="button" @click="openArtifact(item.kind)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="row2e">
        <div class="card">
          <h3>评测</h3>
          <div class="kv">
            <span>人给（可见）</span><span class="mono">{{ visibleSeed(job.seed) }}</span>
            <span>智能体扩充</span><span class="mono">{{ job.seed.agent }}</span>
            <span>隐藏考题</span><span class="lock">🔒 {{ job.seed.holdout }}（只显示条数）</span>
          </div>
          <div style="margin-top: 10px"><RouterLink class="btn sm" to="/eval">在评测中心看门禁结果 →</RouterLink></div>
        </div>
        <div class="card">
          <h3>链路与成本</h3>
          <div class="kv">
            <span>trace</span><span class="mono">一个任务一条，根节点带 keel.devflow.job_id</span>
            <span>花费</span><span class="mono">{{ cny(job.spentCny) }}（薄网关 costCny）</span>
          </div>
          <div style="margin-top: 10px; display: flex; gap: 8px">
            <RouterLink class="btn sm" to="/traces">在链路追踪里看 →</RouterLink>
            <RouterLink class="btn sm" to="/models">在模型网关看成本 →</RouterLink>
          </div>
        </div>
      </div>
    </template>

    <div v-if="modal" class="mask on" @click="modal = null" />
    <div v-if="modal === 'takeover'" class="modal on" role="dialog" aria-label="人工接管">
      <div class="mh">人工接管 {{ jobId }}</div>
      <div class="mb">
        <ul class="plain">
          <li>dev-agent 停止推送，任务进入「人工开发」</li>
          <li>修复轮次暂停计数</li>
          <li>你在 <span class="mono">keel-agents/{{ job?.targetAgent }}</span> 的 PR 上直接提交</li>
          <li>记 config.change 审计（kind=devflow.takeover）</li>
        </ul>
      </div>
      <div class="mf">
        <button class="btn" type="button" @click="modal = null">取消</button>
        <button class="btn vio" type="button" :disabled="busy" @click="run(() => takeoverDevflowJob(jobId), '已接管')">确认接管</button>
      </div>
    </div>
    <div v-if="modal === 'assist'" class="modal on" role="dialog" aria-label="请智能体帮忙">
      <div class="mh">请智能体帮忙</div>
      <div class="mb">
        <div class="field">
          <label>指令（写明范围）</label>
          <textarea v-model="instruction" class="inp" placeholder="如：在 tools/ 下补齐单测，不要改 app.py" />
        </div>
      </div>
      <div class="mf">
        <button class="btn" type="button" @click="modal = null">取消</button>
        <button class="btn pri" type="button" :disabled="busy" @click="confirmAssist">发给 dev-agent</button>
      </div>
    </div>
    <div v-if="modal === 'cancel'" class="modal on" role="dialog" aria-label="取消任务">
      <div class="mh">取消 {{ jobId }}</div>
      <div class="mb">已开通的 staging 资源按逆序回收，仓库和 PR 保留只读。</div>
      <div class="mf">
        <button class="btn" type="button" @click="modal = null">再想想</button>
        <button class="btn danger" type="button" :disabled="busy" @click="run(() => cancelDevflowJob(jobId), '已取消')">确认取消</button>
      </div>
    </div>

    <div class="mask" :class="{ on: manifestOpen }" @click="manifestOpen = false" />
    <aside class="drawer" :class="{ on: manifestOpen }">
      <div class="dh">
        <h3>agent.yaml · {{ job?.targetAgent }}</h3>
        <span class="sp" />
        <button class="x" type="button" @click="manifestOpen = false">×</button>
      </div>
      <div class="db"><pre class="code">{{ yaml }}</pre></div>
    </aside>
  </div>
</template>
