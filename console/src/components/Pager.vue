<script setup lang="ts">
import { computed } from 'vue'

type PageSize = 10 | 20 | 50 | 100

const props = defineProps<{ total: number }>()
const page = defineModel<number>('page', { required: true })
const size = defineModel<PageSize>('size', { required: true })

const SIZES: PageSize[] = [10, 20, 50, 100]

const pages = computed(() => Math.max(1, Math.ceil(props.total / size.value)))
const from = computed(() => (props.total ? (page.value - 1) * size.value + 1 : 0))
const to = computed(() => Math.min(page.value * size.value, props.total))

const numbers = computed<(number | '…')[]>(() => {
  const n = pages.value
  const cur = page.value
  if (n <= 7) return Array.from({ length: n }, (_, i) => i + 1)
  const middle = [cur - 1, cur, cur + 1].filter((p) => p > 1 && p < n)
  return [1, ...(cur > 4 ? (['…'] as const) : []), ...middle, ...(cur < n - 3 ? (['…'] as const) : []), n]
})

function go(p: number) {
  if (p >= 1 && p <= pages.value && p !== page.value) page.value = p
}

function changeSize(event: Event) {
  size.value = Number((event.target as HTMLSelectElement).value) as PageSize
  page.value = 1
}
</script>

<template>
  <div class="pager">
    <div class="size">
      每页
      <select :value="size" @change="changeSize">
        <option v-for="s in SIZES" :key="s" :value="s">{{ s }}</option>
      </select>
      条
    </div>
    <span>第 {{ from }}–{{ to }} 条，共 {{ total }} 条</span>
    <div class="pages">
      <button :disabled="page <= 1" @click="go(page - 1)">‹</button>
      <template v-for="(n, i) in numbers" :key="i">
        <span v-if="n === '…'" class="ell">…</span>
        <button v-else :class="{ on: n === page }" @click="go(n)">{{ n }}</button>
      </template>
      <button :disabled="page >= pages" @click="go(page + 1)">›</button>
    </div>
  </div>
</template>

<style scoped>
.pager { display: flex; align-items: center; gap: 12px; margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--line); font-size: 12.5px; color: var(--mute); flex-wrap: wrap; }
.size { display: flex; align-items: center; gap: 6px; }
select {
  appearance: none;
  background-color: var(--bg);
  background-image: linear-gradient(45deg, transparent 50%, var(--mute) 50%), linear-gradient(135deg, var(--mute) 50%, transparent 50%);
  background-position: calc(100% - 12px) 52%, calc(100% - 8px) 52%;
  background-size: 4px 4px;
  background-repeat: no-repeat;
  border: 1px solid var(--line2);
  border-radius: 6px;
  color: var(--white);
  padding: 3px 22px 3px 8px;
  font-size: 12.5px;
  outline: none;
  cursor: pointer;
}
select:focus { border-color: var(--acc); }
.pages { margin-left: auto; display: flex; align-items: center; gap: 4px; }
.pages button { min-width: 28px; height: 28px; padding: 0 8px; border: 1px solid var(--line2); background: var(--panel2); color: var(--text); border-radius: 6px; font-size: 12px; font-family: var(--mono); cursor: pointer; transition: 0.15s; }
.pages button:hover:not(:disabled):not(.on) { border-color: #3d5070; color: var(--white); }
.pages button.on { background: var(--acc); border-color: var(--acc); color: #1d0e05; font-weight: 700; }
.pages button:disabled { opacity: 0.35; cursor: not-allowed; }
.ell { padding: 0 4px; font-family: var(--mono); }
</style>
