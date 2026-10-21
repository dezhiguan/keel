<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { diagnoseRoute, type ModelGateway, type RouteDiagnosis } from '@/api/models'
import { toKeelError } from '@/api/http'

type GatewayKey = NonNullable<ModelGateway['keys']>[number]
const props = defineProps<{ keys: GatewayKey[]; logicalRoutes: NonNullable<ModelGateway['logicalRoutes']>; revision: number }>()
const alias = ref('')
const advanced = ref(false)
const model = ref('')
const busy = ref(false)
const result = ref<RouteDiagnosis | null>(null)
const error = ref('')
const completed = ref('')
let sequence = 0
const selected = computed(() => props.keys.find(key => key.alias === alias.value))
const choices = computed(() => [...new Set([...props.logicalRoutes.map(row => row.logical ?? ''), ...(selected.value?.models ?? [])])].filter(Boolean))
watch(() => props.keys, keys => {
  if (!keys.some(key => key.alias === alias.value)) {
    alias.value = keys.find(key => key.status !== 'BLOCKED' && ['staging', 'prod'].includes(key.env ?? ''))?.alias
      ?? keys[0]?.alias ?? ''
  }
}, { immediate: true })
function reset() {
  sequence++
  busy.value = false
  result.value = null
  error.value = ''
  completed.value = ''
}
watch([alias, advanced, model, () => props.revision, () => JSON.stringify(selected.value)], reset, { flush: 'sync' })
onUnmounted(() => { sequence++ })
async function simulate() {
  if (busy.value) return
  reset()
  if (!alias.value || (advanced.value && !model.value.trim())) {
    error.value = '请选择 Key；高级模式需填写请求模型名。'
    return
  }
  const mine = ++sequence
  busy.value = true
  try {
    const next = await diagnoseRoute(alias.value, advanced.value ? model.value.trim() : undefined)
    if (mine !== sequence) return
    result.value = next
    completed.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (e) {
    if (mine === sequence) error.value = toKeelError(e).message
  } finally {
    if (mine === sequence) busy.value = false
  }
}
function fallbackLabel(value?: string) {
  return ({ none: '不降级', alternate: '切换备用模型', cost: '按成本选择', latency: '按延迟选择' } as Record<string, string>)[value ?? ''] ?? (value || '不降级')
}
</script>

<template>
  <section class="card route-diagnostic" aria-label="路由试算">
    <h3>路由试算 <small>按已发布规则检查分流、降级与授权，不调用模型</small><span class="sp" />
      <button class="btn pri sm" type="button" :disabled="busy || !alias" @click="simulate">{{ busy ? '试算中…' : '试算' }}</button>
    </h3>
    <div class="share-grid">
      <div class="field"><label for="route-diagnostic-key">虚拟 Key</label>
        <select id="route-diagnostic-key" v-model="alias" class="inp">
          <option v-for="key in keys" :key="key.alias" :value="key.alias">{{ key.alias }}{{ key.status === 'BLOCKED' ? '（已停用）' : '' }}</option>
        </select>
      </div>
      <div class="field"><label>请求模型</label><div class="default-model">{{ advanced ? '使用高级选项指定的模型' : (result?.defaultModel || '自动读取该智能体同环境的默认模型') }}</div></div>
    </div>
    <label class="advanced-toggle"><input v-model="advanced" type="checkbox" /> 高级选项：覆盖请求模型名</label>
    <div v-if="advanced" class="field advanced-model">
      <label for="route-diagnostic-model">请求中的模型名</label>
      <input id="route-diagnostic-model" v-model="model" class="inp mono" list="route-model-options" placeholder="例如 chat-default" />
      <datalist id="route-model-options"><option v-for="name in choices" :key="name" :value="name" /></datalist>
      <small class="mut">逻辑模型按路由表分流；真实模型直接指定调用，降级使用 Key 配置。</small>
    </div>
    <div aria-live="polite" :aria-busy="busy">
      <div v-if="busy" class="banner info">正在读取默认模型与已发布路由…</div>
      <div v-else-if="error" class="banner warn" role="alert">{{ error }}</div>
      <template v-else-if="result">
        <div class="banner" :class="result.blocked ? 'warn' : 'info'">
          已完成 · {{ completed }} · 已发布 v{{ result.version }} · {{ result.env }} ·
          {{ result.requestedModel }}（{{ result.kind === 'logical' ? '逻辑模型' : '真实模型' }}）
          <div class="mut">{{ result.modelSource === 'manifest' ? '来源：同环境智能体登记清单' : '来源：高级选项覆盖' }} · 降级：{{ fallbackLabel(result.fallback) }}</div>
        </div>
        <div v-for="warning in result.warnings" :key="warning" class="banner warn">{{ warning }}</div>
        <div class="diagnostic-table"><table class="t">
          <thead><tr><th>真实模型</th><th>分流比例</th><th>授权</th><th>渠道状态</th><th>选中此模型时的尝试顺序</th></tr></thead>
          <tbody><tr v-for="target in result.targets" :key="target.model">
            <td class="mono">{{ target.model }}</td><td>{{ target.weight ? `${Number(target.weight.toFixed(2))}%` : '仅降级' }}</td>
            <td><span class="pill" :class="target.authorized ? 'p-ok' : 'p-bad'">{{ target.authorized ? '已授权' : '缺少授权' }}</span></td>
            <td>{{ target.breakerOpen ? '熔断中' : '未熔断' }}</td>
            <td>{{ target.warning || (target.attempts?.join(' → ') || '备用候选') }}</td>
          </tr></tbody>
        </table></div>
        <p class="mut">比例包含灰度分流。仅在超时或 5xx 时最多降级一次；本次未检查实时预算、限流或上游连通性。</p>
      </template>
      <div v-else class="banner info">选择 Key 后点击「试算」，自动使用智能体的默认模型。路由发布或修改选择后需重新试算。</div>
    </div>
  </section>
</template>

<style scoped>
.share-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.default-model { min-height: 30px; display: flex; align-items: center; color: var(--text); }
.route-diagnostic h3 { flex-wrap: wrap; row-gap: 8px; }
.advanced-toggle { display: flex; gap: 8px; align-items: center; margin: 14px 0; color: var(--mute); cursor: pointer; }
.advanced-model { margin-bottom: 18px; }
.diagnostic-table { overflow-x: auto; }
.route-diagnostic p { margin: 14px 0 0; }
@media (max-width: 680px) { .share-grid { grid-template-columns: 1fr; } }
</style>
