<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { checkAgentGuard, getAgentGuardrails, saveAgentGuardrails } from '@/api/agents'
import { toKeelError } from '@/api/http'
import {
  DEFAULT_PROBE,
  GATEWAY_GUARDS,
  GUARD_ACTION,
  NORMAL_PROBE,
  SOURCE_LABEL,
  canOpenDebug,
  formatHits,
  probeMessage,
  type GuardCaller,
  type GuardCheck,
  type GuardMode,
  type GuardTraceLine,
  type ProbeSource,
  type ProbeView,
} from './guard'

const route = useRoute()
const router = useRouter()
const name = computed(() => decodeURIComponent(String(route.params.name ?? '')))

const checks = ref<GuardCheck[]>([])
const gateway = ref<GuardCheck[]>(GATEWAY_GUARDS.map((row) => ({ ...row, options: [...row.options], hits: null })))
const source = ref<ProbeSource>('user')
const probe = ref(DEFAULT_PROBE)
const result = ref<ProbeView | null>(null)
const pod = ref('')
const version = ref('')
const latency = ref(0)
const loading = ref(false)
const checking = ref(false)
const saving = ref(false)
const callers = ref<GuardCaller[]>([])
const traceId = ref<string | null>(null)
const trace = ref<GuardTraceLine[]>([])
const hitsKnown = ref(false)

const resultText = computed(() => (result.value && result.value.kind !== 'blocked' ? probeMessage(result.value) : ''))

function applyPage(page: Awaited<ReturnType<typeof getAgentGuardrails>>) {
  checks.value = (page.checks ?? []).map((row) => ({
    ...row,
    options: [...row.options],
    hits: row.hits ?? null,
  }))
  hitsKnown.value = (page.checks ?? []).some((row) => row.hits != null)
  callers.value = page.callers ?? []
  traceId.value = page.traceId ?? null
  trace.value = page.trace ?? []
  version.value = page.version ?? ''
  pod.value = page.instanceId ?? ''
}

async function load(agent: string) {
  loading.value = true
  result.value = null
  try {
    const page = await getAgentGuardrails(agent)
    if (agent !== name.value) return
    applyPage(page)
  } catch (error) {
    if (agent === name.value) ElMessage.error(toKeelError(error).message)
  } finally {
    if (agent === name.value) loading.value = false
  }
}

watch(name, (agent) => {
  source.value = 'user'
  probe.value = DEFAULT_PROBE
  if (agent) load(agent)
}, { immediate: true })

function setMode(rows: GuardCheck[], index: number, mode: GuardMode) {
  const row = rows[index]
  if (!row) return
  row.mode = mode
}

async function save() {
  const modes: Record<string, string> = {}
  for (const row of checks.value) {
    if (row.id) modes[row.id] = row.mode
  }
  saving.value = true
  try {
    applyPage(await saveAgentGuardrails(name.value, modes))
    ElMessage.success('护栏配置已写入注册中心的 agent.yaml，下次部署生效')
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    saving.value = false
  }
}

async function runProbe(text = probe.value) {
  probe.value = text
  checking.value = true
  result.value = null
  try {
    const answer = await checkAgentGuard(name.value, source.value, text)
    result.value = {
      source: answer.source,
      sourceLabel: SOURCE_LABEL[answer.source],
      mode: answer.mode,
      kind: answer.kind,
      tone: answer.tone,
      hit: answer.hit,
    }
    pod.value = answer.instanceId ?? ''
    version.value = answer.version ?? version.value
    latency.value = answer.latencyMs ?? 0
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    checking.value = false
  }
}

function useNormal() {
  runProbe(NORMAL_PROBE)
}

function toDebug() {
  if (!canOpenDebug(source.value)) {
    ElMessage.warning('工具返回和知识库片段只能在这里测；调试页只能发用户输入')
    return
  }
  router.push({ path: `/agents/${encodeURIComponent(name.value)}/debug`, query: { draft: probe.value } })
}

function layerPill(layer: GuardCaller['layer']) {
  if (layer === 'sdk') return { cls: 'p-ok', label: '有' }
  if (layer === 'bypass') return { cls: 'p-warn', label: '绕过 SDK' }
  if (layer === 'none') return { cls: 'p-bad', label: '无' }
  return { cls: 'p-mute', label: '未核实' }
}
</script>

<template>
  <div v-loading="loading" class="guard">
    <div class="vh">
      <h2>{{ name }} · 护栏</h2>
      <span class="sub trail">
        <RouterLink to="/agents">智能体目录</RouterLink>
        ›
        <RouterLink :to="{ path: '/agents', query: { drawer: name } }">{{ name }}</RouterLink>
        ›
        <RouterLink :to="`/agents/${encodeURIComponent(name)}/guard`">护栏</RouterLink>
      </span>
    </div>

    <div class="guard-grid">
      <div class="card">
        <h3>智能体层检查 <small>近 7 天 · 写在 agent.yaml，随部署生效</small></h3>
        <table class="t">
          <thead><tr><th>检查</th><th>模式</th><th>命中</th><th>处理</th></tr></thead>
          <tbody>
            <tr v-for="(row, index) in checks" :key="row.name">
              <td class="gname"><b>{{ row.name }}</b><small>{{ row.detail }}</small></td>
              <td>
                <div class="seg">
                  <button
                    v-for="option in row.options"
                    :key="option"
                    type="button"
                    :class="{ on: option === row.mode }"
                    :data-mode="option"
                    @click="setMode(checks, index, option)"
                  >{{ option }}</button>
                </div>
              </td>
              <td class="mono">{{ formatHits(row.hits) }}</td>
              <td><span class="pill" :class="GUARD_ACTION[row.mode].cls">{{ GUARD_ACTION[row.mode].label }}</span></td>
            </tr>
          </tbody>
        </table>
        <div class="save"><button v-write class="btn pri" type="button" :disabled="saving || !checks.length" @click="save">{{ saving ? '保存中…' : '保存' }}</button></div>
      </div>

      <div class="card">
        <h3>试一下 <small>只跑检测：转发到运行实例，使用当前部署的护栏配置，不进业务、不调模型和工具、不写记忆和审计</small></h3>
        <div class="chipsel src">
          <button type="button" :class="{ on: source === 'user' }" @click="source = 'user'">{{ SOURCE_LABEL.user }}</button>
          <button type="button" :class="{ on: source === 'tool' }" @click="source = 'tool'">{{ SOURCE_LABEL.tool }}</button>
          <button type="button" :class="{ on: source === 'kb' }" @click="source = 'kb'">{{ SOURCE_LABEL.kb }}</button>
        </div>
        <textarea v-model="probe" class="inp probe" />
        <div class="actions">
          <button class="btn pri" type="button" :disabled="checking" @click="runProbe()">{{ checking ? '检测中…' : '检测' }}</button>
          <button class="btn" type="button" @click="useNormal">换正常输入</button>
          <button class="btn" type="button" @click="toDebug">去调试页端到端测 →</button>
        </div>
        <div v-if="result" class="out">
          <div class="banner" :class="result.tone">
            来源 <b>{{ result.sourceLabel }}</b> · 模式 <b>{{ result.mode }}</b><br>
            <template v-if="result.kind === 'blocked'">命中 · 实例规则要求拦截。真实调用会返回 <span class="mono">GUARD_INJECTION_BLOCKED</span>，可到调试页核验。</template>
            <template v-else>{{ resultText }}</template>
            <template v-if="result.hit && result.mode !== 'off'"><br><span class="mut">命中内容不会写入会话记忆</span></template>
          </div>
          <div class="mut meta">运行实例 <span class="mono">{{ pod }}</span> · 配置 {{ version || '—' }} · {{ latency }} ms · 规则检测，未调模型</div>
        </div>
      </div>
    </div>

    <div class="card">
      <h3>网关基线层 <small>所有调用方都经过 keel-llm，按 Key 配置；只用规则不调模型。信任分层、污点、审批仍只在智能体 SDK 里</small></h3>
      <table class="t">
        <thead><tr><th>网关检查</th><th>模式</th><th>近 7 天命中</th></tr></thead>
        <tbody>
          <tr v-for="(row, index) in gateway" :key="row.name">
            <td>{{ row.name }}</td>
            <td>
              <div class="seg">
                <button
                  v-for="option in row.options"
                  :key="option"
                  type="button"
                  :class="{ on: option === row.mode }"
                  :data-mode="option"
                  @click="setMode(gateway, index, option)"
                >{{ option }}</button>
              </div>
            </td>
            <td class="mono">{{ formatHits(row.hits) }}</td>
          </tr>
        </tbody>
      </table>
      <div class="mut callers">虚拟 Key 来自注册中心。网关基线还没有配置，其他调用方是否绕过 SDK 这里不能判断。</div>
      <table class="t">
        <thead><tr><th>调用方</th><th>Key</th><th>智能体层</th></tr></thead>
        <tbody>
          <tr v-if="!callers.length"><td colspan="3" class="mut">还没有登记虚拟 Key</td></tr>
          <tr v-for="row in callers" :key="row.key">
            <td>{{ row.name }}</td>
            <td class="mono">{{ row.key }}</td>
            <td><span class="pill" :class="layerPill(row.layer).cls">{{ layerPill(row.layer).label }}</span></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <h3>调用链路 · 护栏节点 <small v-if="traceId" class="mono">{{ traceId }}</small></h3>
      <p v-if="!trace.length" class="mut">近 7 天没有读到这个智能体的护栏观测。{{ hitsKnown ? '' : '观测服务暂时没有返回，命中数先空着。' }}</p>
      <pre v-else class="code tree"><template v-for="(line, index) in trace" :key="index"><span :class="line.tone">{{ line.text }}</span>
</template></pre>
    </div>
  </div>
</template>

<style scoped>
.guard-grid { display: grid; grid-template-columns: 1.25fr 1fr; gap: 14px; align-items: start; }
.guard-grid > .card { margin-bottom: 14px; }
.trail a { color: var(--soft); text-decoration: none; }
.trail a:hover { color: var(--white); }
.gname b { color: var(--white); font-weight: 500; display: block; }
.gname small { color: var(--mute); font-size: 11.5px; }
.seg { display: inline-flex; background: var(--bg); border: 1px solid var(--line2); border-radius: 8px; padding: 2px; gap: 2px; }
.seg button { border: none; background: none; color: var(--mute); font-size: 12px; padding: 3px 10px; border-radius: 6px; font-family: var(--mono); cursor: pointer; transition: 0.15s; }
.seg button:disabled { cursor: not-allowed; opacity: 0.55; }
.seg button:hover { color: var(--white); }
.seg button.on { background: var(--panel3); color: var(--white); box-shadow: inset 0 0 0 1px var(--line2); }
.seg button.on[data-mode="enforce"],
.seg button.on[data-mode="拦截"] { color: var(--bad); background: rgba(244, 106, 106, 0.12); box-shadow: inset 0 0 0 1px #5a2a2e; }
.seg button.on[data-mode="shadow"],
.seg button.on[data-mode="审批"] { color: var(--warn); background: rgba(241, 180, 76, 0.12); box-shadow: inset 0 0 0 1px #5a4a26; }
.seg button.on[data-mode="mask"] { color: var(--soft); background: rgba(91, 156, 246, 0.12); box-shadow: inset 0 0 0 1px #2a4470; }
.save { margin-top: 10px; }
.src { margin-bottom: 8px; }
.probe { width: 100%; min-height: 80px; resize: vertical; font-family: var(--mono); font-size: 12px; box-sizing: border-box; }
.actions { margin-top: 8px; display: flex; gap: 8px; flex-wrap: wrap; }
.banner { border: 1px solid var(--line); border-radius: 10px; padding: 10px 14px; margin: 10px 0; font-size: 12.8px; }
.banner.bad { border-color: #5a2a2e; background: #1c1216; }
.banner.warn { border-color: #5a4a26; background: #1c1810; }
.banner.ok { border-color: #24483c; background: #0f1a16; }
.meta { font-size: 11.5px; }
.callers { font-size: 12px; margin: 14px 0 6px; }
.tree { background: #0b1220; border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; line-height: 1.75; white-space: pre-wrap; margin: 0; }
.tree .warn { color: var(--warn); }
.tree .soft { color: var(--soft); }
.tree .ok { color: var(--ok); }
@media (max-width: 1100px) { .guard-grid { grid-template-columns: 1fr; } }
</style>
