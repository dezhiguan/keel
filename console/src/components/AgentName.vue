<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useAgentCatalog } from '@/stores/agentCatalog'

const props = defineProps<{ id?: string | null; displayName?: string | null }>()
const catalog = useAgentCatalog()
onMounted(() => { void catalog.ensure() })
const title = computed(() => catalog.title(props.id, props.displayName))
</script>

<template>
  <span v-if="id" class="nm"><b>{{ title }}</b><small class="mono">{{ id }}</small></span>
  <span v-else>—</span>
</template>
