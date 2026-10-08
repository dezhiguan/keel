<script setup lang="ts">
import { computed } from 'vue'
import type { EmployeeCard } from './employees'
import { lineageNodes } from './employees'

const props = defineProps<{ cards: EmployeeCard[] }>()
const emit = defineEmits<{ open: [name: string] }>()

const nodes = computed(() => lineageNodes(props.cards))
const piped = computed(() => nodes.value.piped.slice(0, 2).join(' · ') || '—')
const manual = computed(() => nodes.value.manual.slice(0, 4).join(' · ') || '—')
</script>

<template>
  <div class="card">
    <h3>谱系 <small>生产方向只能向下：人 → 元智能体 → 研发员工 → 业务员工</small></h3>
    <svg viewBox="0 0 980 230" class="lineage">
      <g font-family="PingFang SC, sans-serif" font-size="11.5">
        <rect x="430" y="6" width="120" height="30" rx="7" fill="#141e2f" stroke="#6b7990" />
        <text x="490" y="26" text-anchor="middle" fill="#cdd6e4">人（平台管理员）</text>
        <line x1="490" y1="36" x2="490" y2="56" stroke="#2a3a55" />
        <g class="node" @click="emit('open', 'meta-agent')">
          <rect x="430" y="56" width="120" height="30" rx="7" fill="#1d1610" stroke="#ff7a45" />
          <text x="490" y="76" text-anchor="middle" fill="#eef3fa">meta-agent</text>
        </g>
        <g v-for="(card, index) in nodes.produced" :key="card.name" class="node" @click="emit('open', card.name)">
          <line :x1="490" y1="86" :x2="20 + index * 136 + 60" y2="112" stroke="#2a3a55" />
          <rect :x="20 + index * 136" y="112" width="120" height="28" rx="7" fill="#141e2f" :stroke="card.color" />
          <text :x="20 + index * 136 + 60" y="131" text-anchor="middle" fill="#cdd6e4" font-family="SF Mono, Menlo, monospace" font-size="10.5">{{ card.name }}</text>
        </g>
        <line x1="80" y1="140" x2="80" y2="164" stroke="#2a3a55" />
        <rect x="20" y="164" width="560" height="26" rx="7" fill="#0f1a19" stroke="#1f4f4b" stroke-dasharray="4 3" />
        <text x="300" y="181" text-anchor="middle" fill="#2ec4b6">dev-lead 编排 → {{ piped }} · …</text>
        <rect x="600" y="164" width="360" height="26" rx="7" fill="#0f141c" stroke="#2a3a55" stroke-dasharray="4 3" />
        <text x="780" y="181" text-anchor="middle" fill="#8b98ad">人工编写 / 迁移：{{ manual }} · …</text>
        <text x="490" y="218" text-anchor="middle" fill="#6b7990" font-size="10.5">点节点打开智能体详情；改造任务只能由上一层发起，任何智能体不能改自己</text>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.lineage { width: 100%; }
.node { cursor: pointer; }
</style>
