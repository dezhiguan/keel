<script setup lang="ts">
defineProps<{ label: string; value: string; hint?: string }>()
</script>

<template>
  <div class="kpi">
    <div class="label">{{ label }}</div>
    <div class="value">{{ value }}</div>
    <div v-if="hint" class="hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.kpi {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 14px 16px;
}
.label { color: var(--mute); font-size: 12px; }
.value { color: var(--white); font-size: 26px; font-weight: 600; margin-top: 6px; font-family: var(--mono); }
.hint { color: var(--mute); font-size: 12px; margin-top: 4px; }
</style>
