<script setup lang="ts">
import { computed } from 'vue'
import type { DevflowJob } from '@/api/devflow'
import { statusView } from './jobs'

const props = defineProps<{ job: Pick<DevflowJob, 'status' | 'stage' | 'layer' | 'needReview' | 'humanDevUser' | 'watchDay'> }>()
const view = computed(() => statusView(props.job))
</script>

<template>
  <span class="pill" :class="view.cls">{{ view.label }}</span>
</template>
