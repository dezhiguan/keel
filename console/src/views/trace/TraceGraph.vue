<script setup lang="ts">
defineProps<{ nodes: unknown[] }>()
</script>

<!-- TODO(P1-16): 协作图，SVG 自绘，不引图形库。 -->
<template>
  <svg class="graph" />
</template>
