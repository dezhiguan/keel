<script setup lang="ts">
import { ringColor, ringDash } from '../approvalGroups'

defineProps<{ score: number; minScore: number; label: string; sub: string }>()
</script>

<template>
  <div class="ring">
    <svg viewBox="0 0 76 76" width="76" height="76" aria-hidden="true">
      <circle cx="38" cy="38" r="30" stroke="#1d2940" stroke-width="7" fill="none" />
      <circle
        cx="38"
        cy="38"
        r="30"
        stroke-width="7"
        fill="none"
        :style="{ stroke: ringColor(score, minScore) }"
        :stroke-dasharray="ringDash(score)"
        transform="rotate(-90 38 38)"
        stroke-linecap="round"
      />
    </svg>
    <div>
      <b>{{ score.toFixed(2) }}</b>
      <span class="mut">{{ label }}<br />{{ sub }}</span>
    </div>
  </div>
</template>

<style scoped>
.ring { display: flex; align-items: center; gap: 12px; }
.ring b { display: block; font-family: var(--mono); font-size: 22px; color: var(--white); }
.ring span { font-size: 12px; }
</style>
