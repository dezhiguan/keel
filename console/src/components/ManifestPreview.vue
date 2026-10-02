<script setup lang="ts">
defineProps<{ yaml: string }>()
</script>

<!-- TODO(P1-15): syntax highlighting and schema errors from manifest-preview. -->
<template>
  <pre class="manifest">{{ yaml }}</pre>
</template>

<style scoped>
.manifest {
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
