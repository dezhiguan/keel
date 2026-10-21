<script setup lang="ts">
import { computed } from 'vue'
import { renderMessageMarkdown } from '@/utils/markdown'

const props = defineProps<{ text: string }>()
const html = computed(() => renderMessageMarkdown(props.text))
</script>

<template>
  <div class="message-markdown" v-html="html"></div>
</template>

<style scoped>
.message-markdown {
  min-width: 0;
  max-width: 100%;
  white-space: normal;
  overflow-wrap: anywhere;
  line-height: 1.75;
}
.message-markdown :deep(p) { margin: 0 0 0.8em; }
.message-markdown :deep(h1),
.message-markdown :deep(h2),
.message-markdown :deep(h3),
.message-markdown :deep(h4),
.message-markdown :deep(h5),
.message-markdown :deep(h6) {
  display: block;
  margin: 1.2em 0 0.5em;
  color: var(--white);
  font-weight: 600;
  line-height: 1.5;
}
.message-markdown :deep(h1) { font-size: 1.35em; }
.message-markdown :deep(h2) { font-size: 1.2em; }
.message-markdown :deep(h3),
.message-markdown :deep(h4),
.message-markdown :deep(h5),
.message-markdown :deep(h6) { font-size: 1.05em; }
.message-markdown :deep(strong) { color: var(--white); font-weight: 600; }
.message-markdown :deep(ul),
.message-markdown :deep(ol) { margin: 0 0 0.8em; padding-left: 1.7em; }
.message-markdown :deep(li) { padding-left: 0.2em; margin: 0.35em 0; }
.message-markdown :deep(li::marker) { color: var(--mute); }
.message-markdown :deep(li > p) { margin-bottom: 0.4em; }
.message-markdown :deep(li > ul),
.message-markdown :deep(li > ol) { margin: 0.3em 0; }
.message-markdown :deep(a) { color: var(--soft); text-decoration: underline; text-underline-offset: 3px; }
.message-markdown :deep(a:focus-visible),
.message-markdown :deep(.markdown-table:focus-visible) { outline: 2px solid var(--soft); outline-offset: 2px; }
.message-markdown :deep(blockquote) {
  margin: 0 0 0.8em;
  border-left: 3px solid var(--line2);
  padding: 0.2em 0 0.2em 1em;
  color: var(--mute);
}
.message-markdown :deep(code) {
  font-family: var(--mono);
  font-size: 0.9em;
  padding: 0.15em 0.35em;
  border-radius: 4px;
  background: var(--bg);
}
.message-markdown :deep(pre) {
  max-width: 100%;
  box-sizing: border-box;
  overflow-x: auto;
  white-space: pre;
  overflow-wrap: normal;
  margin: 0 0 0.8em;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--bg);
  line-height: 1.6;
}
.message-markdown :deep(pre code) { padding: 0; background: none; }
.message-markdown :deep(.markdown-table) { max-width: 100%; overflow-x: auto; margin-bottom: 0.8em; }
.message-markdown :deep(table) { width: 100%; border-collapse: collapse; font-size: 0.95em; }
.message-markdown :deep(th),
.message-markdown :deep(td) { border: 1px solid var(--line2); padding: 7px 10px; text-align: left; min-width: 5em; }
.message-markdown :deep(th) { color: var(--white); background: var(--bg); font-weight: 600; }
.message-markdown :deep(hr) { border: 0; border-top: 1px solid var(--line2); margin: 1em 0; }
.message-markdown :deep(> :first-child) { margin-top: 0; }
.message-markdown :deep(> :last-child),
.message-markdown :deep(blockquote > :last-child),
.message-markdown :deep(li > :last-child) { margin-bottom: 0; }
</style>
