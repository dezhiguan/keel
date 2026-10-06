<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NAV } from '@/router/nav'
import { listAgents } from '@/api/agents'
import { listTools } from '@/api/tools'

const open = defineModel<boolean>({ required: true })
const router = useRouter()
const query = ref('')
const index = ref(0)
const input = ref<HTMLInputElement | null>(null)
const agents = ref<{ name: string; displayName: string }[]>([])
const tools = ref<{ name: string; owner: string }[]>([])

type Item = { type: string; title: string; sub: string; to: string }

const items = computed<Item[]>(() => {
  const pages: Item[] = [
    ...NAV.map((item) => ({ type: '页面', title: item.title, sub: item.path, to: item.path })),
    { type: '操作', title: '新建智能体', sub: '向导', to: '/agents/new' },
  ]
  const agentItems = agents.value.map((agent) => ({
    type: '智能体',
    title: agent.displayName || agent.name,
    sub: agent.name,
    to: `/agents?drawer=${encodeURIComponent(agent.name)}`,
  }))
  const toolItems = tools.value.map((tool) => ({
    type: '工具',
    title: tool.name,
    sub: tool.owner,
    to: `/tools?tool=${encodeURIComponent(tool.name)}`,
  }))
  const q = query.value.trim().toLowerCase()
  return [...pages, ...agentItems, ...toolItems]
    .filter((item) => !q || `${item.title} ${item.sub}`.toLowerCase().includes(q))
    .slice(0, 12)
})

async function refresh() {
  try {
    const page = await listAgents({ env: 'all', category: 'all', page: 1, size: 100 })
    agents.value = (page.items ?? []).flatMap((agent) => agent.name ? [{ name: agent.name, displayName: agent.displayName || agent.name }] : [])
  } catch {
    agents.value = []
  }
  try {
    const page = await listTools({ scope: 'all', page: 1, size: 100 })
    tools.value = (page.items ?? []).flatMap((tool) => tool.name ? [{ name: tool.name, owner: tool.ownerOrg || tool.ownerAgent || '' }] : [])
  } catch {
    tools.value = []
  }
}

function close() {
  open.value = false
}

function go(item?: Item) {
  if (!item) return
  close()
  router.push(item.to)
}

function onKey(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    open.value = !open.value
    return
  }
  if (!open.value) return
  if (event.key === 'Escape') close()
  if (event.key === 'ArrowDown') {
    index.value = Math.min(index.value + 1, Math.max(items.value.length - 1, 0))
    event.preventDefault()
  }
  if (event.key === 'ArrowUp') {
    index.value = Math.max(index.value - 1, 0)
    event.preventDefault()
  }
  if (event.key === 'Enter') go(items.value[index.value])
}

watch(open, async (shown) => {
  if (!shown) return
  query.value = ''
  index.value = 0
  refresh()
  await nextTick()
  input.value?.focus()
})

watch(query, () => { index.value = 0 })

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div v-if="open" class="mask" @click="close" />
  <div v-if="open" class="pal" role="dialog" aria-label="搜索">
    <input ref="input" v-model="query" placeholder="输入智能体、工具或页面名称…" autocomplete="off" />
    <ul>
      <li v-if="!items.length" class="mut">没有匹配项</li>
      <li v-for="(item, i) in items" :key="`${item.type}-${item.to}`" :class="{ on: i === index }" @click="go(item)">
        <span class="ty">{{ item.type }}</span>{{ item.title }}<small>{{ item.sub }}</small>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.mask { position: fixed; inset: 0; background: rgba(3, 6, 12, 0.6); z-index: 40; }
.pal { position: fixed; left: 50%; top: 14vh; transform: translateX(-50%); width: min(560px, 92vw); background: var(--panel); border: 1px solid var(--line2); border-radius: 14px; z-index: 70; box-shadow: 0 30px 80px rgba(0, 0, 0, 0.55); }
.pal input { width: 100%; box-sizing: border-box; background: none; border: none; border-bottom: 1px solid var(--line); color: var(--white); font-size: 15px; padding: 14px 18px; outline: none; font-family: inherit; }
.pal ul { max-height: 340px; overflow: auto; padding: 6px; margin: 0; }
.pal li { list-style: none; display: flex; gap: 10px; align-items: center; padding: 8px 12px; border-radius: 8px; cursor: pointer; font-size: 13px; }
.pal li small { margin-left: auto; color: var(--mute); font-size: 11.5px; }
.pal li.on { background: var(--panel3); color: var(--white); }
.pal li .ty { font-size: 10.5px; color: var(--mute); border: 1px solid var(--line2); border-radius: 4px; padding: 0 5px; min-width: 42px; text-align: center; }
.mut { color: var(--mute); cursor: default; }
</style>
