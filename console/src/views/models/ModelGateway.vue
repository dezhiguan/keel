<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import { getModelGateway, type ModelGateway } from '@/api/models'
import { toKeelError } from '@/api/http'
import { useEnvStore } from '@/stores/env'
import { fmtN, fmtPct, type StatusTone } from '@/utils/format'

const MODEL_STATUS: Record<string, { label: string; tone: StatusTone }> = {
  ok: { label: '正常', tone: 'ok' },
  warn: { label: '波动', tone: 'degraded' },
  bad: { label: '异常', tone: 'failed' },
}

const envStore = useEnvStore()
const data = ref<ModelGateway | null>(null)
const tab = ref<'model' | 'key'>('model')
const loading = ref(false)

const usage = (spent = 0, budget = 1) => Math.min(100, (spent / budget) * 100)
const barColor = (p: number) => (p > 80 ? 'var(--bad)' : p > 60 ? 'var(--warn)' : 'var(--teal)')

async function load() {
  loading.value = true
  try {
    data.value = await getModelGateway({ env: envStore.env, range: '24h' })
  } catch (error) {
    ElMessage.error(`加载模型网关失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

function adjustBudget(agent: string) {
  // TODO(P1-8): budget changes go through keel-server once the thin gateway exposes an update API.
  ElMessage.info(`${agent} 的预算调整尚未接入`)
}

watch(() => envStore.env, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>模型网关</h2>
      <span class="sub">数据来自薄网关的人民币花费明细</span>
    </div>
    <div class="chipsel" style="margin-bottom: 14px">
      <button :class="{ on: tab === 'model' }" @click="tab = 'model'">模型 · {{ data?.models?.length ?? 0 }}</button>
      <button :class="{ on: tab === 'key' }" @click="tab = 'key'">虚拟 Key 与日预算 · {{ data?.keys?.length ?? 0 }}</button>
    </div>

    <div v-if="tab === 'model'" class="card">
      <h3>模型</h3>
      <table class="t">
        <thead><tr><th>模型</th><th>提供方</th><th>用途</th><th>24h 调用</th><th>P95</th><th>错误率</th><th>成本</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="m in data?.models ?? []" :key="m.model">
            <td class="mono">{{ m.model }}<span v-if="m.priceConfigured === false" class="pill nd p-bad" style="margin-left: 6px">未配单价</span></td>
            <td>{{ m.provider }}</td>
            <td>{{ m.role }}</td>
            <td class="mono">{{ fmtN(m.calls) }}</td>
            <td class="mono">{{ m.p95 }}</td>
            <td class="mono" :style="m.status === 'warn' ? { color: 'var(--warn)' } : undefined">{{ fmtPct(m.errorRate) }}</td>
            <td class="mono">{{ m.priceConfigured === false || m.costCny == null ? '—' : `¥${m.costCny.toFixed(2)}` }}</td>
            <td><StatusPill v-bind="MODEL_STATUS[m.status ?? 'ok']" /></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-else class="card">
      <h3>虚拟 Key 与日预算<small>keel register 自动创建，keel retire 自动吊销</small></h3>
      <table class="t">
        <thead><tr><th>智能体</th><th>Key 别名</th><th>可用模型</th><th style="width: 34%">今日 / 日预算</th><th>状态</th><th /></tr></thead>
        <tbody>
          <tr v-for="k in data?.keys ?? []" :key="k.alias">
            <td>{{ k.agent }}</td>
            <td class="mono">{{ k.alias }}</td>
            <td class="mono">{{ k.models?.length }}</td>
            <td>
              <div style="display: flex; justify-content: space-between; font-size: 12px">
                <span class="mono">¥{{ k.spentCny?.toFixed(2) }}</span><span class="mono mut">¥{{ k.dailyBudgetCny }}</span>
              </div>
              <div class="bar"><i :style="{ width: `${usage(k.spentCny, k.dailyBudgetCny)}%`, background: barColor(usage(k.spentCny, k.dailyBudgetCny)) }" /></div>
            </td>
            <td>
              <StatusPill v-if="k.status === 'BLOCKED'" tone="idle" label="已吊销" />
              <StatusPill v-else-if="usage(k.spentCny, k.dailyBudgetCny) > 60" tone="degraded" :label="`${Math.round(usage(k.spentCny, k.dailyBudgetCny))}%`" />
              <StatusPill v-else tone="ok" label="有效" />
            </td>
            <td><button v-if="k.status !== 'BLOCKED'" class="btn sm" @click="adjustBudget(k.agent!)">调整预算</button></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
