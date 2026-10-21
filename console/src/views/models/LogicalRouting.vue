<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getLogicalRouteTable, saveLogicalRouteTable, operateLogicalRouteTable, replayLogicalRouteTable,
  type LogicalRule, type LogicalRouteSnapshot, type RouteReplay } from '@/api/models'
import { toKeelError } from '@/api/http'
import { useUserStore } from '@/stores/user'
import { useEnvStore } from '@/stores/env'

const emit = defineEmits<{ changed: []; keys: [] }>()
const user = useUserStore()
const env = useEnvStore()
const admin = computed(() => user.user?.platformRole === 'ADMIN')
const state = ref<LogicalRouteSnapshot | null>(null)
const draft = ref<LogicalRule[]>([])
const busy = ref(false)
const error = ref('')
const replay = ref<RouteReplay | null>(null)
const editor = ref<LogicalRule | null>(null)
const editIndex = ref(-1)
let disposed = false
const dirty = computed(() => !!state.value && JSON.stringify(draft.value) !== JSON.stringify(state.value.draft))
const visible = computed(() => draft.value.map((row, index) => ({ row, index })).filter(({ row }) => env.env === 'all' || row.env === env.env))
const distributions = computed(() => [...new Set([...Object.keys(replay.value?.published ?? {}), ...Object.keys(replay.value?.draft ?? {})])].sort())
function accept(next: LogicalRouteSnapshot) {
  if (disposed) return
  state.value = next
  draft.value = next.draft.map(row => ({ ...row }))
  replay.value = null
}
async function load() {
  busy.value = true
  error.value = ''
  try { accept(await getLogicalRouteTable()) }
  catch (e) { error.value = toKeelError(e).message }
  finally { busy.value = false }
}
async function save() {
  if (!state.value || !dirty.value) return
  accept(await saveLogicalRouteTable(state.value.revision, draft.value))
}
async function action(op: 'save' | 'publish' | 'rollback' | 'grants' | 'replay') {
  if (!state.value || busy.value || !admin.value) return
  if (op === 'rollback') {
    try { await ElMessageBox.confirm('恢复上一版完整路由表，当前未发布草稿会被替换。', '回滚路由表', { type: 'warning' }) }
    catch { return }
  }
  busy.value = true
  error.value = ''
  try {
    if (op !== 'rollback') await save()
    if (op === 'replay') replay.value = await replayLogicalRouteTable(state.value.revision)
    else if (op !== 'save') {
      accept(await operateLogicalRouteTable(op, state.value.revision))
      emit('changed')
      ElMessage.success(op === 'grants' ? '已补授权；有渠道份额的 Key 新增渠道为 1 RPM，可在 Key 与配额调整' : `路由表已${op === 'publish' ? '发布' : '回滚'} v${state.value.version}`)
    } else ElMessage.success('草稿已保存，尚未生效')
  } catch (e) { error.value = toKeelError(e).message }
  finally { busy.value = false }
}
function edit(index = -1) {
  editIndex.value = index
  const model = state.value?.models?.[0] ?? ''
  editor.value = index >= 0 ? { ...draft.value[index] } : {
    logical: '', env: env.env === 'prod' ? 'prod' : 'staging', primary: model, secondary: model,
    primaryWeight: 100, fallback: 'none', grayPercent: 0, grayModel: '', minInputTokens: 0,
  }
}
function applyEdit() {
  if (!editor.value) return
  if (draft.value.some((r, i) => i !== editIndex.value && r.logical === editor.value!.logical && r.env === editor.value!.env)) {
    ElMessage.error('该逻辑模型和环境已有规则'); return
  }
  if (editIndex.value >= 0) draft.value[editIndex.value] = { ...editor.value }
  else draft.value.push({ ...editor.value })
  editor.value = null
  replay.value = null
}
function remove(index: number) { draft.value.splice(index, 1); replay.value = null }
function rowGaps(row: LogicalRule) { return state.value?.gaps.filter(g => g.logical === row.logical && (row.env === 'prod' ? g.alias.endsWith('-prod') : !g.alias.endsWith('-prod'))) ?? [] }
function fallbackName(mode: string) { return ({ none: '无', alternate: '另一模型', latency: '按延迟', cost: '按成本' } as Record<string, string>)[mode] ?? mode }
onMounted(load)
onUnmounted(() => { disposed = true })
</script>

<template>
  <div v-loading="busy" class="logical-routing">
    <div v-if="error" class="banner warn" role="alert">{{ error }} <button class="btn sm" :disabled="busy" @click="load">重新加载</button></div>
    <template v-if="state">
      <div class="card">
        <h3>路由表 <span class="pill nd p-soft mono">已发布 v{{ state.version }}</span>
          <small>{{ dirty ? '有未保存修改' : '草稿未发布前不生效' }}</small><span class="sp" />
          <template v-if="admin">
            <button v-write class="btn sm" :disabled="busy" @click="edit()">新增逻辑模型</button>
            <button v-write class="btn sm" :disabled="busy || !dirty" @click="action('save')">保存草稿</button>
            <button v-write class="btn sm" :disabled="busy || !state.canRollback" @click="action('rollback')">回滚</button>
            <button v-write class="btn pri sm" :disabled="busy" @click="action('publish')">发布</button>
          </template>
        </h3>
        <div class="route-scroll"><table class="t">
          <thead><tr><th>逻辑模型</th><th>条件</th><th>主模型权重</th><th>次模型权重</th><th>降级</th><th>灰度</th><th>Key 覆盖</th><th v-if="admin">操作</th></tr></thead>
          <tbody>
            <tr v-for="{ row, index } in visible" :key="`${row.logical}-${row.env}`">
              <td class="mono">{{ row.logical }}</td>
              <td>env={{ row.env }}<small v-if="row.minInputTokens"> · 估算输入 ≥ {{ row.minInputTokens }}</small></td>
              <td><div class="weight"><span class="mono">{{ row.primary }}</span><input v-if="admin" v-model.number="row.primaryWeight" aria-label="主模型权重" type="range" min="0" max="100" :disabled="busy" @input="replay = null" /><b>{{ row.primaryWeight }}%</b></div></td>
              <td class="mono">{{ row.secondary }} {{ 100 - row.primaryWeight }}%</td>
              <td>{{ fallbackName(row.fallback) }}</td>
              <td>{{ row.grayPercent ? `${row.grayModel} ${row.grayPercent}%` : '—' }}</td>
              <td><span v-if="dirty" class="pill p-warn">保存后检查</span><span v-else-if="rowGaps(row).length" class="pill p-bad">{{ rowGaps(row).length }} 把 Key 未覆盖</span><span v-else class="pill p-ok">全部覆盖</span></td>
              <td v-if="admin" class="actions"><button v-write class="btn sm" :disabled="busy" @click="edit(index)">编辑</button><button v-write class="btn sm" :disabled="busy" @click="remove(index)">删除</button></td>
            </tr>
            <tr v-if="!visible.length"><td colspan="8" class="mut">当前环境没有逻辑模型规则</td></tr>
          </tbody>
        </table></div>
        <div v-if="state.references.length" class="pbanner bad">不能删除仍被引用的规则：{{ state.references.join('；') }}</div>
        <div v-if="state.gaps.length" class="pbanner bad">
          发布前要补授权：<span v-for="gap in state.gaps" :key="`${gap.alias}-${gap.logical}`"><b class="mono">{{ gap.alias }}</b> 缺 {{ gap.models.join('、') }}；</span>
          <p class="mut">否则权重、灰度或降级选中这些模型时请求发不出去。</p>
          <button v-if="admin" v-write class="btn sm" :disabled="busy" @click="action('grants')">一键补授权</button>
          <button class="btn sm" @click="emit('keys')">去 Key 与配额</button>
        </div>
        <div v-else class="pbanner ok">{{ dirty ? '保存草稿后检查授权和引用。' : '路由可能选到的每个模型，相关 Key 均已覆盖。' }} 逻辑模型使用网关降级策略；超时或 5xx 最多切换一次，401、403 和预算不足不降级。</div>
      </div>
      <div class="card">
        <h3>回放试算 <small>近 24 小时真实请求，按草稿重新路由</small><span class="sp" /><button v-if="admin" v-write class="btn pri sm" :disabled="busy" @click="action('replay')">回放</button></h3>
        <p v-if="!replay" class="mut">调整规则后点「回放」。仅计算路由和费用，不调用模型。</p>
        <template v-else>
          <p class="mut">{{ replay.requests }} 次请求；{{ replay.pricedRequests }} 次具备结算用量。旧账单缺少逻辑模型记录 {{ replay.legacyRequests }} 次，未纳入回放。最多保留 {{ replay.recordLimit.toLocaleString() }} 条请求元数据。</p>
          <table class="t"><thead><tr><th>指标</th><th>已发布 v{{ replay.version }}</th><th>草稿</th></tr></thead><tbody>
            <tr v-for="name in distributions" :key="name"><td>{{ name }} 请求数</td><td>{{ replay.published[name] ?? 0 }}</td><td>{{ replay.draft[name] ?? 0 }}</td></tr>
            <tr><td>样本预计费用</td><td>¥{{ Number(replay.publishedEstimatedCny).toFixed(6) }}</td><td>¥{{ Number(replay.draftEstimatedCny).toFixed(6) }}</td></tr>
            <tr><td>主路由缺少授权</td><td>—</td><td>{{ replay.missingAuthorization }} 次</td></tr>
            <tr><td>规则缺失或输入阈值不匹配</td><td>—</td><td>{{ replay.unmatched }} 次</td></tr>
          </tbody></table>
          <p class="mut">费用按真实 token 和当前单价估算；保留相同请求的稳定分流。回放不预测故障、厂商限流或输出变化。</p>
        </template>
      </div>
    </template>
    <Teleport to="body">
      <div v-if="editor" class="mask on" @click="editor = null" />
      <form v-if="editor" class="modal on route-editor" role="dialog" aria-label="编辑逻辑模型" @submit.prevent="applyEdit">
        <div class="mh">{{ editIndex < 0 ? '新增逻辑模型' : '编辑逻辑模型' }}</div>
        <div class="mb fields">
          <label>逻辑名称<input v-model="editor.logical" class="inp" required pattern="[a-z][a-z0-9-]{1,62}" /></label>
          <label>适用环境<select v-model="editor.env" class="inp"><option value="staging">staging</option><option value="prod">prod</option></select></label>
          <label>主模型<select v-model="editor.primary" class="inp"><option v-for="m in state?.models" :key="m">{{ m }}</option></select></label>
          <label>次模型<select v-model="editor.secondary" class="inp"><option v-for="m in state?.models" :key="m">{{ m }}</option></select></label>
          <label>主模型权重（%）<input v-model.number="editor.primaryWeight" class="inp" type="number" min="0" max="100" required /></label>
          <label>降级策略<select v-model="editor.fallback" class="inp"><option value="none">无</option><option value="alternate">另一模型</option><option value="latency">按延迟</option><option value="cost">按成本</option><option v-for="m in state?.models" :key="m" :value="m">{{ m }}</option></select></label>
          <label>灰度比例（%，0 为关闭）<input v-model.number="editor.grayPercent" class="inp" type="number" min="0" max="100" required /></label>
          <label>灰度模型<select v-model="editor.grayModel" class="inp" :required="!!editor.grayPercent"><option value="">未启用</option><option v-for="m in state?.models" :key="m">{{ m }}</option></select></label>
          <label>最小估算输入量（0 为不限）<input v-model.number="editor.minInputTokens" class="inp" type="number" min="0" required /></label>
        </div>
        <div class="mf"><button class="btn" type="button" @click="editor = null">取消</button><button class="btn pri" type="submit">应用到草稿</button></div>
      </form>
    </Teleport>
  </div>
</template>
<style scoped>
.route-scroll { overflow-x: auto; }
.weight { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; min-width: 200px; }
.weight input { width: 110px; accent-color: var(--acc); }
.actions { white-space: nowrap; }
.actions .btn + .btn, .pbanner .btn + .btn { margin-left: 6px; }
.logical-routing h3 { flex-wrap: wrap; row-gap: 8px; }
.route-editor { width: min(680px, 90vw); max-height: 90vh; overflow-y: auto; }
.fields { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.fields label { display: flex; flex-direction: column; gap: 6px; color: var(--mute); font-size: 12px; }
.fields .inp { width: 100%; box-sizing: border-box; }
@media(max-width: 680px) { .fields { grid-template-columns: 1fr; } }
</style>
