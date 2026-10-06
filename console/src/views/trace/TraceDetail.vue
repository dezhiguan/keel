<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusPill from '@/components/StatusPill.vue'
import TraceGraph from './TraceGraph.vue'
import TraceLanes from './TraceLanes.vue'
import TraceTree from './TraceTree.vue'
import { getTrace, type TraceDetail } from '@/api/traces'
import { KeelApiError, toKeelError } from '@/api/http'
import { fmtCny, fmtMs, nodeStatus } from '@/utils/format'
import { agentMap, humanWait, initialTab, longestStep, nodeMap, observationUrl, pickNode, showTokens, userLine, type TraceTab } from './traceView'

const TABS: [TraceTab, string][] = [['graph', '协作图'], ['lanes', '泳道时间线'], ['tree', '调用树']]

const route = useRoute()
const router = useRouter()
const detail = ref<TraceDetail | null>(null)
const missing = ref(false)
const loading = ref(false)
const tab = ref<TraceTab>('lanes')
const selected = ref<string | null>(null)

const agents = computed(() => (detail.value ? agentMap(detail.value) : {}))
const node = computed(() => (detail.value && selected.value ? nodeMap(detail.value)[selected.value] : null))
const summary = computed(() => detail.value?.summary)
const multi = computed(() => !!summary.value?.multiAgent)
const tabs = computed(() => TABS.filter(([key]) => key !== 'graph' || multi.value))
const costTotal = computed(() => (detail.value?.costBreakdown ?? []).reduce((n, c) => n + (c.costCny ?? 0), 0) || 1)
const tokensOf = computed(() => node.value?.tokens ?? ((node.value?.tokensIn ?? 0) + (node.value?.tokensOut ?? 0) || null))
const nodeUrl = computed(() => observationUrl(detail.value?.langfuseUrl, node.value?.id))
const bottleneck = computed(() => longestStep(detail.value?.latencyBreakdown, summary.value?.durationMs))

async function load() {
  loading.value = true
  missing.value = false
  detail.value = null
  try {
    detail.value = await getTrace(String(route.params.id))
    const nodes = detail.value.nodes ?? []
    tab.value = initialTab(!!detail.value.summary?.multiAgent)
    selected.value = pickNode(nodes)
  } catch (error) {
    detail.value = null
    const keel = toKeelError(error)
    if (keel instanceof KeelApiError && keel.code === 'SERVER_NOT_FOUND') {
      missing.value = true
    } else {
      ElMessage.error(`加载链路失败：${keel.message}`)
    }
  } finally {
    loading.value = false
  }
}

watch(() => route.params.id, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <button class="btn sm" @click="router.push('/traces')">← 链路列表</button>
      <h2 class="mono">{{ route.params.id }}</h2>
      <span class="sp" />
      <a v-if="detail?.langfuseUrl" class="btn" :href="detail.langfuseUrl" target="_blank" rel="noopener">在 Langfuse 中打开 ↗</a>
    </div>

    <div v-if="missing" class="card empty">
      没有找到这条链路。可能已超过 Langfuse 30 天保留期，或属于其他环境、评测运行。
      <div style="margin-top: 12px"><button class="btn" @click="router.push('/traces')">返回链路列表</button></div>
    </div>

    <template v-else-if="detail && summary">
      <div class="card tsum">
        <div class="q">{{ summary.question }}</div>
        <div class="metas">
          <span>trace <b>{{ summary.traceId }}</b></span>
          <span v-if="summary.runId">run <b>{{ summary.runId }}<template v-if="summary.suspendCount"> · 挂起 {{ summary.suspendCount }} 次</template></b></span>
          <span v-if="summary.sessionId">会话 <b>{{ summary.sessionId }}<template v-if="summary.turn != null"> · 第 {{ summary.turn }} 轮</template></b></span>
          <span v-if="userLine(summary.userId, summary.userRole)">用户 <b>{{ userLine(summary.userId, summary.userRole) }}</b></span>
          <span v-if="multi">参与智能体 <b>{{ summary.agents?.length }}</b></span>
          <span v-else>智能体 <b>{{ summary.rootAgent }}</b></span>
          <span v-if="!multi && summary.env">环境 <b>{{ summary.env }}</b></span>
          <span>智能体耗时 <b>{{ fmtMs(summary.durationMs) }}</b></span>
          <span v-if="summary.humanWaitMs">人工等待 <b>{{ humanWait(summary.humanWaitMs) }}</b></span>
          <span>tokens <b>{{ showTokens(summary.tokens) }}</b></span>
          <span>成本 <b>{{ fmtCny(summary.costCny) }}</b></span>
          <span v-if="summary.pendingReason">状态 <b class="pend">挂起中 · {{ summary.pendingReason }}</b></span>
        </div>
        <div class="contrib" :class="{ single: !multi || !detail.costBreakdown?.length }">
          <div>
            <div class="lb"><span>{{ multi ? '耗时构成（关键路径）' : '耗时构成（按步骤）' }}</span><span class="mono">{{ fmtMs(summary.durationMs) }}</span></div>
            <div class="stack">
              <i v-for="(l, i) in detail.latencyBreakdown" :key="i" :style="{ width: `${((l.ms ?? 0) / (summary.durationMs || 1)) * 100}%`, background: agents[l.agentKey!]?.color }" />
            </div>
            <div class="keys">
              <span v-for="(l, i) in detail.latencyBreakdown" :key="i"><i :style="{ background: agents[l.agentKey!]?.color }" />{{ l.label }} {{ fmtMs(l.ms) }}</span>
            </div>
          </div>
          <div v-if="multi && detail.costBreakdown?.length">
            <div class="lb"><span>成本构成（按智能体）</span><span class="mono">{{ fmtCny(summary.costCny) }}</span></div>
            <div class="stack">
              <i v-for="c in detail.costBreakdown" :key="c.agentKey" :style="{ width: `${((c.costCny ?? 0) / costTotal) * 100}%`, background: agents[c.agentKey!]?.color }" />
            </div>
            <div class="keys">
              <span v-for="c in detail.costBreakdown" :key="c.agentKey"><i :style="{ background: agents[c.agentKey!]?.color }" />{{ agents[c.agentKey!]?.name }} {{ fmtCny(c.costCny) }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <h3>
          <div class="vtabs">
            <button v-for="[k, n] in tabs" :key="k" :class="{ on: tab === k }" @click="tab = k">{{ n }}</button>
          </div>
          <small v-if="!multi" style="margin-left: 8px">单智能体调用没有委派关系，不显示协作图</small>
          <span class="sp" />
          <small>点击任意节点查看详情</small>
        </h3>
        <TraceGraph v-if="multi && tab === 'graph'" :detail="detail" :selected="selected" @select="selected = $event" />
        <template v-else-if="tab === 'lanes'">
          <TraceLanes :detail="detail" :selected="selected" @select="selected = $event" />
          <div class="critnote">
            <template v-if="multi && bottleneck">带白色下划线的是<b>关键路径</b>。{{ bottleneck.label }} 占智能体耗时 {{ bottleneck.pct }}%。并行的步骤不在这条路径上。</template>
            <template v-else>带白色下划线的是<b>关键路径</b>，虚线框为降级。单智能体调用按顺序执行，关键路径就是整条链路。</template>
          </div>
        </template>
        <TraceTree v-else :detail="detail" :selected="selected" @select="selected = $event" />

        <div v-if="node" class="ndetail">
          <div><span>节点</span><b>{{ node.name }}</b></div>
          <div><span>Langfuse 类型</span><b>{{ node.type }}</b></div>
          <div><span>所属智能体</span><b :style="{ color: agents[node.agentKey!]?.color }">{{ agents[node.agentKey!]?.name ?? '—' }}</b></div>
          <div><span>服务</span><b>{{ node.service ?? '—' }}</b></div>
          <div><span>状态</span><b><StatusPill v-bind="nodeStatus(node.status)" /></b></div>
          <div><span>开始</span><b>{{ fmtMs(node.startMs) }}</b></div>
          <div><span>耗时</span><b>{{ node.humanWaitLabel ? `人工 ${node.humanWaitLabel}` : fmtMs(node.durationMs) }}</b></div>
          <div><span>模型</span><b>{{ node.model ?? '—' }}{{ node.fallbackFrom ? `（从 ${node.fallbackFrom} 降级）` : '' }}</b></div>
          <div><span>tokens{{ node.aggregated ? '（含子节点）' : '' }}</span><b>{{ showTokens(tokensOf) }}</b></div>
          <div><span>成本{{ node.aggregated ? '（含子节点）' : '' }}</span><b>{{ fmtCny(node.costCny) }}</b></div>
          <div v-if="node.model" class="full"><span>模型网关</span><b>经薄网关<template v-if="node.llmKeyAlias"> · 虚拟 Key {{ node.llmKeyAlias }}</template> · 花费计入该智能体的日预算</b></div>
          <div class="full"><span>输入摘要</span><b style="white-space: pre-wrap">{{ node.inputSummary }}</b></div>
          <div class="full"><span>输出摘要</span><b style="white-space: pre-wrap">{{ node.outputSummary }}</b></div>
          <div v-if="node.promptFallback" class="full">
            <span>提示词</span>
            <b><span class="pill p-warn">本地兜底副本</span> {{ node.promptName || '本地副本' }}（Langfuse 读取失败，链路上没有关联版本）</b>
          </div>
          <div v-else-if="node.promptName" class="full">
            <span>提示词</span>
            <b><RouterLink :to="`/prompts/${node.promptName}`" style="color: var(--soft)">{{ node.promptName }} v{{ node.promptVersion }} →</RouterLink></b>
          </div>
          <div class="full">
            <span>关联</span>
            <div class="acts">
              <span v-if="node.gateNote" class="pill p-soft">{{ node.gateNote }}</span>
              <span v-for="a in node.auditIds" :key="a" class="pill p-soft">审计事件 {{ a }}</span>
              <RouterLink v-if="node.approvalId" to="/approvals" class="pill p-soft">审批记录 {{ node.approvalId }}</RouterLink>
              <span v-if="!node.gateNote && !node.auditIds?.length && !node.approvalId" class="mut">—</span>
              <span class="sp" />
              <a v-if="nodeUrl" class="btn" :href="nodeUrl" target="_blank" rel="noopener">在 Langfuse 中查看此节点 ↗</a>
            </div>
          </div>
        </div>

        <div class="legend2">
          <span>颜色 = 所属智能体：</span>
          <span v-for="a in detail.agents" :key="a.key"><i :style="{ background: a.color }" />{{ a.name }}</span>
          <span style="margin-left: auto">
            状态点：<b style="color: var(--ok)">●</b> ok　<b style="color: var(--warn)">●</b> 降级　<b style="color: var(--bad)">●</b> 失败
          </span>
        </div>
        <div class="srcnote">
          <template v-if="multi">多个智能体是独立服务，靠 W3C traceparent 串成同一个 trace。这个页面在 Langfuse 之上按智能体拆分耗时和成本、标出关键路径，并把审计事件和审批记录挂到对应节点上。</template>
          <template v-else>详情只请求一次 keel-server，泳道和调用树用同一份节点。trace 里出现两个以上智能体（子智能体带 <code>keel.parent_agent</code>）时，才多出协作图、按智能体拆分的耗时和成本。</template>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tsum .q { font-size: 14.5px; color: #fff; font-weight: 600; margin-bottom: 8px; line-height: 1.5; }
.metas { display: flex; flex-wrap: wrap; gap: 6px 16px; font-size: 12px; color: var(--mute); }
.metas b { color: #fff; font-family: var(--mono); font-weight: 500; }
.contrib { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 14px; }
.contrib.single { grid-template-columns: 1fr; }
.pend { color: var(--warn); }
.contrib .lb { font-size: 11.5px; color: var(--mute); margin-bottom: 5px; display: flex; justify-content: space-between; }
.vtabs { display: flex; gap: 3px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 3px; }
.vtabs button { all: unset; cursor: pointer; padding: 4px 12px; border-radius: 6px; font-size: 12px; color: var(--mute); font-weight: 400; }
.vtabs button.on { background: #223049; color: #fff; }
.critnote { font-size: 11.5px; color: var(--mute); margin-top: 8px; }
.critnote b { color: #fff; font-weight: 500; }
.ndetail { margin-top: 14px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 12px 14px; display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px 16px; font-size: 12px; }
.ndetail span { display: block; color: var(--mute); font-size: 11px; }
.ndetail b { font-weight: 500; color: #e6ebf3; font-family: var(--mono); font-size: 12px; word-break: break-all; }
.ndetail b > span { display: inline-flex; font-size: 11.5px; }
.ndetail .full { grid-column: 1 / -1; }
.ndetail .full b { font-family: inherit; font-weight: 400; }
.ndetail .acts { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.ndetail .acts .pill { display: inline-flex; color: var(--soft); text-decoration: none; }
.legend2 { display: flex; gap: 14px; margin-top: 10px; font-size: 11px; color: var(--mute); flex-wrap: wrap; }
.legend2 i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 5px; vertical-align: -1px; }
</style>
