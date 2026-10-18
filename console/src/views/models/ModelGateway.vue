<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import {
  allocated,
  allocationPercent,
  allocationTone,
  breakerView,
  buildReject,
  channelHost,
  channelShort,
  createModelCostState,
  fixCoverage,
  insertChannel,
  inspectShare,
  missingModels,
  MODELS,
  probeChannel,
  prodQwenChannels,
  publishRoute,
  replayDraft,
  replayGapKeys,
  rollbackRoute,
  routeGaps,
  simulatedKeyIndex,
  switchProdQwen,
  tripChannel,
  uniqueGaps,
  type Channel,
  type ReplayView,
} from './modelCost'

type Tab = 'ch' | 'key' | 'route'
type ToastKind = 'ok' | 'warn' | 'bad' | 'info'
type Toast = { id: number; text: string; kind: ToastKind }

const state = reactive(createModelCostState())
const tab = ref<Tab>('ch')
const selected = ref(0)
const draft = reactive<Record<number, number>>({})
const tpm = ref(0)
const concurrency = ref(0)
const clock = ref(Date.now())
const modal = ref<'add' | 'switch' | null>(null)
const channelName = ref('百炼·主账号B·生产空间')
const channelModel = ref<string>(MODELS[0])
const channelEnv = ref('prod')
const channelRpm = ref(1200)
const switchTo = ref(0)
const rejects = ref<{ time: string; alias: string; tone: string; label: string; reason: string }[]>([])
const replay = ref<ReplayView | null>(null)
const toasts = ref<Toast[]>([])
let toastSeq = 0
let timer = 0

const selectedKey = computed(() => state.keys[selected.value])
const share = computed(() => inspectShare(selectedKey.value, draft, state.keys, state.channels, state.rows))
const switchChoices = computed(() => prodQwenChannels(state.channels))
const switchHint = computed(() => (switchChoices.value.length < 2
  ? '只有一个 prod 千问渠道。先「新增渠道」加一个独立主账号或子业务空间。'
  : '把 prod Key 在其他千问渠道上的份额整体挪到目标渠道，网关热生效，不重新部署。'))
const coverageHoles = computed(() => uniqueGaps(state.rows, state.keys, state.channels))
const saveDisabled = computed(() => share.value.over || share.value.empty)

function toast(text: string, kind: ToastKind = 'ok') {
  const id = toastSeq + 1
  toastSeq = id
  toasts.value.push({ id, text, kind })
  window.setTimeout(() => {
    toasts.value = toasts.value.filter((item) => item.id !== id)
  }, 3200)
}

function replaceDraft(next: Record<number, number>) {
  Object.keys(draft).forEach((key) => { delete draft[Number(key)] })
  Object.entries(next).forEach(([key, value]) => { draft[Number(key)] = value })
}

function editKey(index: number) {
  selected.value = index
  const key = state.keys[index]
  replaceDraft({ ...key.sh })
  tpm.value = key.tpm
  concurrency.value = key.c
}

function refreshEditor() {
  editKey(selected.value)
}

function breaker(channel: Channel) {
  return breakerView(channel, clock.value)
}

function allocationOf(index: number) {
  const channel = state.channels[index]
  const used = allocated(state.keys, index)
  const percent = allocationPercent(used, channel.rpm)
  return { used, percent, ...allocationTone(percent) }
}

function keyMiss(index: number) {
  return missingModels(state.keys[index], state.channels, state.rows)
}

function rowGaps(index: number) {
  return routeGaps(state.rows[index], state.keys, state.channels)
}

function rowGapKeys(index: number) {
  return new Set(rowGaps(index).map((gap) => gap.key.a)).size
}

function trip(index: number) {
  const channel = state.channels[index]
  tripChannel(channel, Date.now())
  clock.value = Date.now()
  toast(`「${channel.n}」连续 2 次 5xx，熔断；同地址的其他渠道不受影响`, 'warn')
}

function probe(index: number) {
  const channel = state.channels[index]
  probeChannel(channel)
  clock.value = Date.now()
  toast(`「${channel.n}」探测成功，恢复正常`)
}

function toggleChannel(index: number, on: boolean) {
  if (on) draft[index] = 50
  else delete draft[index]
}

function setShare(index: number, raw: string) {
  draft[index] = Number(raw) || 0
}

function saveKey() {
  if (saveDisabled.value) return
  const key = selectedKey.value
  key.sh = { ...draft }
  key.tpm = Number(tpm.value) || 0
  key.c = Number(concurrency.value) || 0
  refreshEditor()
  toast(`${key.a} 的渠道和份额已下发到网关`)
}

function simulate() {
  const index = simulatedKeyIndex(Math.random(), state.keys.length)
  const vendor = Math.random() < 0.6
  const result = buildReject(state.keys, state.channels, index, vendor, Date.now())
  const time = new Date().toTimeString().slice(0, 8)
  rejects.value.unshift({ time, ...result })
  rejects.value = rejects.value.slice(0, 6)
}

function addChannel() {
  insertChannel(state.channels, {
    n: channelName.value,
    m: channelModel.value,
    env: channelEnv.value,
    rpm: Number(channelRpm.value) || 0,
  })
  modal.value = null
  refreshEditor()
  toast('渠道已新增；Key 要勾选它才会用到')
}

function openSwitch() {
  switchTo.value = switchChoices.value[0]?.index ?? 0
  modal.value = 'switch'
}

function applySwitch() {
  const choice = state.channels[switchTo.value]
  if (!choice) return
  const moved = switchProdQwen(state.keys, state.channels, switchTo.value)
  modal.value = null
  refreshEditor()
  toast(moved ? `已把 ${moved} 份 prod 千问份额切到「${choice.n}」` : '没有需要切换的 Key', moved ? 'ok' : 'info')
}

function applyCoverage() {
  const result = fixCoverage(state.keys, state.channels, state.rows)
  refreshEditor()
  if (result.added) toast(`已为 ${result.added} 处补授权，每处 RPM 50，可在 Key 与配额里调整`)
  if (result.fails.length) toast(`补不上：${result.fails.join('；')}。先新增渠道或调低权重`, 'bad')
}

function runReplay() {
  const row = state.rows[0]
  replay.value = replayDraft(row.w, replayGapKeys(row, state.keys, state.channels))
}

function publish() {
  const result = publishRoute(state)
  if (!result.ok) {
    toast(`有 ${result.keys} 把 Key 未覆盖路由目标，不能发布。先补授权`, 'bad')
    return
  }
  toast(`路由表已发布 v${result.version}，网关热生效`)
}

function rollback() {
  if (!rollbackRoute(state)) {
    toast('只有一版，不能回滚', 'warn')
    return
  }
  refreshEditor()
  toast(`已回到 v${state.ver}`, 'info')
}

editKey(0)

onMounted(() => {
  timer = window.setInterval(() => {
    if (state.channels.some((channel) => channel.br?.until)) clock.value = Date.now()
  }, 1000)
})

onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <div class="models">
    <div class="vh">
      <h2>模型与成本</h2>
      <span class="sub">薄网关 keel-llm · 2 副本</span>
      <span class="tag-prop">提案</span>
    </div>

    <div class="dtabs">
      <button type="button" :class="{ on: tab === 'ch' }" @click="tab = 'ch'">模型与渠道</button>
      <button type="button" :class="{ on: tab === 'key' }" @click="tab = 'key'">Key 与配额</button>
      <button type="button" :class="{ on: tab === 'route' }" @click="tab = 'route'">路由</button>
    </div>

    <div v-show="tab === 'ch'">
      <div class="todo">
        <b>要做</b>拆出「渠道」= 厂商 + 账号/业务空间 + 密钥 + 地址，一个模型挂多个渠道；渠道配厂商 RPM（每分钟请求数）/ TPM（每分钟 Token 数）作天花板，网关 Redis 按渠道计数，超出在网关拒绝；按环境绑定渠道；<b>熔断按渠道计</b>，同一上游地址的不同账号互不影响；<b>厂商 429 改打同模型另一渠道</b>（最多一次，和换模型共用额度；流式未开始输出才换；网关自身份额 429 不换；尊重 Retry-After），需先改技术文档和 external-apis.mdc；Batch 单独通道不占实时份额。百炼子业务空间与 Batch 接口先实测。
      </div>
      <div class="card">
        <h3>
          渠道
          <span class="sp" />
          <button class="btn sm" type="button" @click="openSwitch">切换 prod 渠道</button>
          <button class="btn pri sm" type="button" @click="modal = 'add'">+ 新增渠道</button>
        </h3>
        <table class="t">
          <thead>
            <tr>
              <th>渠道</th>
              <th>模型</th>
              <th>绑定环境</th>
              <th title="RPM：每分钟请求数">厂商上限 RPM<br><small>每分钟请求数</small></th>
              <th>已分配</th>
              <th>当前用量</th>
              <th>状态</th>
              <th>熔断（按渠道）</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="(channel, index) in state.channels" :key="`${channel.n}-${index}`">
              <td class="nm"><b>{{ channel.n }}</b><small>{{ channelHost(channel.n) }}</small></td>
              <td>{{ channel.m }}</td>
              <td>{{ channel.env }}</td>
              <template v-if="channel.batch">
                <td class="mut">不占实时份额</td>
                <td>—</td>
                <td class="mono">3 个批次</td>
                <td><span class="pill nd p-soft">Batch</span></td>
                <td class="mut">—</td>
                <td />
              </template>
              <template v-else>
                <td class="mono">{{ channel.rpm }}</td>
                <td>
                  <div class="bar"><i :style="{ width: `${Math.min(allocationOf(index).percent, 100)}%`, background: allocationOf(index).color }" /></div>
                  <span class="mono alloc">{{ allocationOf(index).used }} / {{ channel.rpm }} · {{ allocationOf(index).percent }}%</span>
                </td>
                <td class="mono">{{ channel.use }}%</td>
                <td><span class="pill" :class="allocationOf(index).tone">{{ allocationOf(index).label }}</span></td>
                <td>
                  <span class="pill" :class="breaker(channel).tone">{{ breaker(channel).label }}</span>
                  <span class="mono mut">{{ breaker(channel).remain }}</span>
                </td>
                <td>
                  <button v-if="breaker(channel).label === '正常'" class="btn sm" type="button" @click="trip(index)">模拟 2 次 5xx</button>
                  <button v-else-if="breaker(channel).label.startsWith('半开')" class="btn sm" type="button" @click="probe(index)">放行探测</button>
                </td>
              </template>
            </tr>
          </tbody>
        </table>
        <div class="mut note">两个百炼渠道共用 dashscope.aliyuncs.com。点「模拟 2 次 5xx」只熔断该渠道 60 秒（演示缩短为 10 秒），之后半开放行一次探测。</div>
      </div>
    </div>

    <div v-show="tab === 'key'">
      <div class="row2">
        <div class="card">
          <h3>Key 份额 <small>一把 Key 可允许多个渠道，每个渠道单独一份 RPM；点一行编辑</small></h3>
          <div class="mut legend">RPM = 每分钟请求数 · TPM = 每分钟 Token 数（输入 + 输出）· 并发 = 同时进行中的请求数</div>
          <table class="t">
            <thead>
              <tr>
                <th>Key</th>
                <th title="RPM：每分钟请求数">允许的渠道 · RPM（每分钟请求数）</th>
                <th>路由覆盖</th>
                <th title="TPM：每分钟 Token 数">TPM（每分钟 Token 数）</th>
                <th title="同时进行中的请求数">并发</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(key, index) in state.keys"
                :key="key.a"
                class="click"
                :class="{ sel: index === selected }"
                @click="editKey(index)"
              >
                <td class="mono">{{ key.a }}</td>
                <td>
                  <span v-for="cursor in Object.keys(key.sh)" :key="cursor" class="pill nd p-mute share">{{ channelShort(state.channels[Number(cursor)].n) }} · {{ key.sh[Number(cursor)] }}</span>
                </td>
                <td>
                  <span v-if="keyMiss(index).length" class="pill p-bad">缺 {{ keyMiss(index).join('、') }}</span>
                  <span v-else class="pill p-ok">已覆盖</span>
                </td>
                <td class="mono">{{ key.tpm.toLocaleString('zh-CN') }}</td>
                <td class="mono">{{ key.c }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="card">
          <h3>分配份额 · <span class="mono">{{ selectedKey.a }}</span></h3>
          <div class="field">
            <label>允许的渠道（只列同环境）</label>
            <div>
              <template v-for="(channel, index) in state.channels" :key="`${channel.n}-${index}`">
                <div v-if="!channel.batch && channel.env === selectedKey.env" class="ch-row">
                  <label>
                    <input type="checkbox" :checked="index in draft" @change="toggleChannel(index, ($event.target as HTMLInputElement).checked)">
                    {{ channel.n }}
                    <span class="mut">{{ channel.m }}</span>
                  </label>
                  <input
                    class="inp rpm"
                    type="number"
                    :value="index in draft ? draft[index] : ''"
                    :disabled="!(index in draft)"
                    placeholder="RPM"
                    title="RPM：每分钟请求数"
                    @input="setShare(index, ($event.target as HTMLInputElement).value)"
                  >
                </div>
              </template>
            </div>
          </div>
          <div class="share-grid">
            <div class="field">
              <label>TPM（每分钟 Token 数，输入 + 输出）</label>
              <input v-model.number="tpm" class="inp" type="number">
            </div>
            <div class="field">
              <label>并发</label>
              <input v-model.number="concurrency" class="inp" type="number">
            </div>
          </div>
          <div v-if="share.missing.length" class="pbanner bad">
            路由表会给 {{ selectedKey.env }} 选到 <b>{{ share.missing.join('、') }}</b>，本 Key 没有允许该模型的任何渠道，选中后请求发不出去。<br>
            <span class="mut">涉及：{{ share.uses }}</span>
          </div>
          <div v-else class="pbanner ok">已覆盖路由表可能选到的模型：{{ share.covered.join('、') || '无' }}。</div>
          <div v-if="share.empty" class="pbanner bad">至少允许一个渠道。</div>
          <div v-else class="pbanner" :class="share.over ? 'bad' : 'ok'">
            <div v-for="line in share.lines" :key="line.name">
              {{ line.name }}：每分钟请求数（RPM）合计 <b class="mono">{{ line.total }} / {{ line.limit }}</b>
              <span v-if="line.over" class="over">超出 {{ line.total - line.limit }}</span>
              <template v-else> · 剩余 {{ line.limit - line.total }}</template>
            </div>
            <span class="mut">{{ share.over ? '请降低份额、申请提额，或改用独立子业务空间。' : '超出份额的请求在网关返回 LLM_RATE_LIMITED，不打到厂商。' }}</span>
          </div>
          <button class="btn pri" type="button" :disabled="saveDisabled" @click="saveKey">保存</button>
        </div>
      </div>
      <div class="card">
        <h3>
          最近被拒绝的请求
          <small>区分网关份额和厂商限流</small>
          <span class="sp" />
          <button class="btn sm" type="button" @click="simulate">模拟一次请求</button>
        </h3>
        <table class="t">
          <thead><tr><th>时间</th><th>Key</th><th>结果</th><th>原因</th></tr></thead>
          <tbody>
            <tr v-for="(row, index) in rejects" :key="`${row.time}-${index}`">
              <td class="mono">{{ row.time }}</td>
              <td class="mono">{{ row.alias }}</td>
              <td><span class="pill" :class="row.tone">{{ row.label }}</span></td>
              <td class="mut">{{ row.reason }}</td>
            </tr>
            <tr v-if="!rejects.length"><td colspan="4" class="mut">点「模拟一次请求」</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-show="tab === 'route'">
      <div class="todo">
        <b>要做</b>现在只是故障降级（请求必须写具体模型，超时/5xx 换一次）。加逻辑模型和路由表：先硬约束（能力、上下文、环境），再按权重 / 成本 / 延迟 / 渠道剩余额度；规则带版本，可灰度、回滚、用历史请求回放试算。<b>路由和 Key 要对上</b>：权重可能选到的每个模型，相关 Key 都必须允许至少一个该模型的同环境渠道，否则选中了也发不出去；发布时校验，不覆盖就不让发布，可一键补授权。第 4、5 项共用渠道模型，同一个 spec。
      </div>
      <div class="card">
        <h3>
          路由表
          <span class="pill nd p-soft mono">已发布 v{{ state.ver }}</span>
          <small>草稿未发布前不生效</small>
          <span class="sp" />
          <button class="btn sm" type="button" @click="rollback">回滚</button>
          <button class="btn pri sm" type="button" @click="publish">发布</button>
        </h3>
        <table class="t">
          <thead>
            <tr>
              <th>逻辑模型</th>
              <th>条件</th>
              <th>qwen3.8-flash 权重</th>
              <th>deepseek-flash</th>
              <th>降级</th>
              <th>灰度</th>
              <th>Key 覆盖</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in state.rows" :key="`${row.l}-${row.c}`">
              <td class="mono">{{ row.l }}</td>
              <td>{{ row.c }}</td>
              <td class="weight">
                <input v-model.number="row.w" type="range" min="0" max="100">
                <span class="mono">{{ row.w }}%</span>
              </td>
              <td class="mono">{{ 100 - row.w }}%</td>
              <td>{{ row.fb }}</td>
              <td>
                <span v-if="row.g === '—'" class="mut">—</span>
                <span v-else class="pill nd p-soft">{{ row.g }}</span>
              </td>
              <td>
                <span
                  v-if="rowGapKeys(index)"
                  class="pill p-bad"
                  :title="rowGaps(index).map((gap) => `${gap.key.a} 缺 ${gap.model}`).join('；')"
                >{{ rowGapKeys(index) }} 把 Key 未覆盖</span>
                <span v-else class="pill p-ok">全部覆盖</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="coverageHoles.length" class="pbanner bad">
          发布前要补授权：<template v-for="(hole, index) in coverageHoles" :key="hole.key.a + hole.model"><template v-if="index">；</template><span class="mono">{{ hole.key.a }}</span> 缺 <b>{{ hole.model }}</b></template>。<br>
          <span class="mut">否则权重选中这些模型时请求发不出去。</span>
          <button class="btn sm fix" type="button" @click="applyCoverage">一键补授权</button>
          <button class="btn sm fix" type="button" @click="tab = 'key'">去 Key 与配额</button>
        </div>
        <div v-else class="pbanner ok">路由表可能选到的每个模型，相关 Key 都至少允许了一个同环境渠道。</div>
      </div>
      <div class="card">
        <h3>
          回放试算
          <small>近 24 小时 12,480 次请求，按草稿重新路由</small>
          <span class="sp" />
          <button class="btn pri sm" type="button" @click="runReplay">回放</button>
        </h3>
        <div v-if="!replay" class="mut">调整上面的权重后点「回放」。</div>
        <template v-else>
          <div class="flow">
            <span class="pill nd p-mute">chat-default</span>
            <span>→</span>
            <span class="pill nd p-mute">硬约束：工具调用 ✓ 上下文 ✓</span>
            <span>→</span>
            <span class="pill nd p-mute">env=prod</span>
            <span>→</span>
            <span class="pill nd p-mute">Key 允许的渠道</span>
            <span>→</span>
            <span class="pill nd p-mute">生产空间剩余 15%</span>
            <span>→</span>
            <span class="pill nd p-acc">qwen {{ replay.qwen }}% / deepseek {{ replay.deepseek }}%</span>
          </div>
          <table class="t">
            <thead><tr><th>指标</th><th>已发布 v{{ state.ver }}</th><th>草稿</th></tr></thead>
            <tbody>
              <tr><td>qwen3.8-flash 占比</td><td class="mono">80%</td><td class="mono">{{ replay.qwen }}%</td></tr>
              <tr>
                <td>预计日成本</td>
                <td class="mono">¥41.20</td>
                <td class="mono">¥{{ replay.cost }} <span class="pill nd" :class="replay.deltaUp ? 'p-warn' : 'p-ok'">{{ replay.deltaUp ? '+' : '' }}{{ replay.delta }}%</span></td>
              </tr>
              <tr><td>预计厂商 429</td><td class="mono">37 次</td><td class="mono">{{ replay.errors }} 次</td></tr>
              <tr>
                <td>因 Key 未覆盖发不出去</td>
                <td class="mono">—</td>
                <td class="mono"><span v-if="replay.lost" class="over">{{ replay.lost }} 次</span><template v-else>0 次</template></td>
              </tr>
            </tbody>
          </table>
        </template>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="modal" class="mask on" @click="modal = null" />
      <div v-if="modal === 'add'" class="modal on wide-ch" role="dialog" aria-label="新增渠道">
        <div class="mh">新增渠道</div>
        <div class="mb">
          <div class="field">
            <label>名称</label>
            <input v-model="channelName" class="inp">
          </div>
          <div class="add-grid">
            <div class="field">
              <label>模型</label>
              <select v-model="channelModel" class="inp">
                <option v-for="model in MODELS" :key="model" :value="model">{{ model }}</option>
              </select>
            </div>
            <div class="field">
              <label>绑定环境</label>
              <select v-model="channelEnv" class="inp">
                <option value="prod">prod</option>
                <option value="dev/test/staging">dev/test/staging</option>
              </select>
            </div>
            <div class="field">
              <label>厂商上限 RPM（每分钟请求数）</label>
              <input v-model.number="channelRpm" class="inp" type="number">
            </div>
          </div>
          <div class="mut secret">密钥只写入 Secret keel-llm-vendors，页面不显示。</div>
        </div>
        <div class="mf">
          <button class="btn" type="button" @click="modal = null">取消</button>
          <button class="btn pri" type="button" @click="addChannel">新增</button>
        </div>
      </div>
      <div v-if="modal === 'switch'" class="modal on wide-ch" role="dialog" aria-label="切换 prod 渠道">
        <div class="mh">切换 prod 渠道</div>
        <div class="mb">
          <div class="field">
            <label>prod Key 改走</label>
            <select v-model.number="switchTo" class="inp">
              <option v-for="choice in switchChoices" :key="choice.index" :value="choice.index">
                {{ choice.channel.n }}（上限 {{ choice.channel.rpm }}，已分配 {{ allocated(state.keys, choice.index) }}）
              </option>
            </select>
          </div>
          <div class="mut secret">{{ switchHint }}</div>
        </div>
        <div class="mf">
          <button class="btn" type="button" @click="modal = null">取消</button>
          <button class="btn pri" type="button" @click="applySwitch">切换</button>
        </div>
      </div>
      <div class="toasts">
        <div v-for="item in toasts" :key="item.id" class="toast" :class="item.kind">{{ item.text }}</div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.vh { margin-bottom: 14px; }
.tag-prop { display: inline-block; font-size: 10.5px; line-height: 17px; background: rgba(180, 140, 242, 0.14); color: var(--violet); border: 1px solid #4a3a6e; border-radius: 4px; padding: 0 6px; font-weight: 500; }
.todo { border: 1px dashed #4a3a6e; background: rgba(180, 140, 242, 0.05); border-radius: 10px; padding: 10px 14px; margin-bottom: 14px; font-size: 12.5px; }
.todo b { color: var(--violet); font-weight: 500; margin-right: 6px; }
.row2 { grid-template-columns: 1.25fr 1fr; }
.pbanner { display: block; margin: 10px 0; }
.field { margin: 10px 0; }
th small { font-weight: 400; }
.bar { min-width: 90px; }
.t tr.sel { background: #131d2e; }
.alloc { font-size: 11.5px; }
.note { font-size: 11.5px; margin-top: 8px; }
.legend { font-size: 11.5px; margin: -6px 0 8px; }
.share { margin: 1px 2px; }
.ch-row { display: flex; align-items: center; gap: 10px; padding: 5px 0; border-bottom: 1px solid #172235; }
.ch-row label { display: flex; gap: 8px; align-items: center; flex: 1; margin: 0; color: var(--text); font-size: 12.8px; cursor: pointer; }
.ch-row .mut { font-size: 11.5px; }
.ch-row .rpm { width: 90px; flex: none; }
.share-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.add-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 10px; }
.over { color: var(--bad); }
.weight { white-space: nowrap; }
.weight input { accent-color: var(--acc); width: 110px; vertical-align: middle; }
.fix { margin-top: 6px; }
.flow { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 10px; font-size: 12px; align-items: center; }
.secret { font-size: 12px; }
.toasts { position: fixed; right: 22px; bottom: 22px; display: flex; flex-direction: column; gap: 8px; z-index: 80; }
.toast { background: var(--panel3); border: 1px solid var(--line2); border-left: 3px solid var(--ok); border-radius: 9px; padding: 10px 14px; color: var(--white); font-size: 13px; max-width: 380px; box-shadow: 0 10px 30px rgba(0, 0, 0, 0.4); }
.toast.warn { border-left-color: var(--warn); }
.toast.bad { border-left-color: var(--bad); }
.toast.info { border-left-color: var(--soft); }
</style>

<style>
.modal.wide-ch { width: min(560px, 92vw); }
</style>
