<script setup lang="ts">
import type { StatusTone } from '@/utils/format'

defineProps<{ tone: StatusTone; label: string }>()
</script>

<template>
  <span class="status-pill" :class="tone"><i />{{ label }}</span>
</template>

<style scoped>
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 1px 9px;
  border-radius: 10px;
  font-size: 11.5px;
  white-space: nowrap;
  background: rgba(139, 152, 173, 0.1);
  color: #8b98ad;
}
.status-pill i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.ok { color: var(--ok); background: rgba(52, 195, 143, 0.1); }
.degraded { color: var(--warn); background: rgba(241, 180, 76, 0.1); }
.failed { color: var(--bad); background: rgba(244, 106, 106, 0.1); }
.soft { color: var(--soft); background: rgba(91, 156, 246, 0.1); }
</style>
