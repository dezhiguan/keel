<script setup lang="ts">
defineProps<{ value: unknown }>()
</script>

<template>
  <pre class="json">{{ JSON.stringify(value, null, 2) }}</pre>
</template>

<style scoped>
.json {
  margin: 0;
  padding: 12px;
  background: var(--panel2);
  border: 1px solid var(--line);
  border-radius: 6px;
  font-family: var(--mono);
  font-size: 12px;
  overflow: auto;
}
</style>
