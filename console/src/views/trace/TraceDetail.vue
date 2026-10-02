<script setup lang="ts">
import PagePlaceholder from '@/components/PagePlaceholder.vue'
import TraceGraph from './TraceGraph.vue'
import TraceLanes from './TraceLanes.vue'
import TraceTree from './TraceTree.vue'
</script>

<!-- TODO(P1-16): fetch /insight/traces/{traceId} once and pass the same nodes to all three views. -->
<template>
  <PagePlaceholder title="链路详情" task="P1-16" />
  <TraceGraph :nodes="[]" />
  <TraceLanes :nodes="[]" />
  <TraceTree :nodes="[]" />
</template>
