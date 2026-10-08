<script setup lang="ts">
import { computed } from 'vue'
import type { DevflowJob } from '@/api/devflow'
import { stripMarks } from './jobs'

const props = defineProps<{ job: Pick<DevflowJob, 'status' | 'stage'> }>()
const marks = computed(() => stripMarks(props.job))
</script>

<template>
  <div class="strip">
    <i v-for="(mark, index) in marks" :key="index" :class="mark.cls" :title="mark.title" />
  </div>
</template>
