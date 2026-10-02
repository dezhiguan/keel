<script setup lang="ts">
import type { StatusTone } from '@/utils/format'

defineProps<{ tone: StatusTone; label: string }>()
</script>

<template>
  <span class="pill" :class="tone"><i />{{ label }}</span>
</template>

<style scoped>
.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 9px;
  border-radius: 10px;
  font-size: 12px;
  background: var(--panel3);
  color: var(--mute);
}
.pill i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.ok { color: var(--ok); }
.degraded { color: var(--warn); }
.failed { color: var(--bad); }
</style>
