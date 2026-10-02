<script setup lang="ts">
defineProps<{ title: string; task?: string }>()
</script>

<template>
  <div class="placeholder">
    <h2>{{ title }}</h2>
    <p>页面尚未实现<span v-if="task">，见任务 {{ task }}</span>。</p>
  </div>
</template>

<style scoped>
.placeholder {
  background: var(--panel);
  border: 1px dashed var(--line2);
  border-radius: 10px;
  padding: 48px;
  text-align: center;
  color: var(--mute);
}
h2 { color: var(--white); margin: 0 0 8px; font-size: 18px; }
p { margin: 0; }
</style>
