import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listAgents } from '@/api/agents'

/** Id to display name for every registered agent. Lists use this so a row can show both. */
export const useAgentCatalog = defineStore('agentCatalog', () => {
  const titles = ref<Record<string, string>>({})
  let pending: Promise<void> | null = null

  function ensure() {
    if (pending) return pending
    pending = listAgents({ env: 'all', page: 1, size: 100 }).then((page) => {
      const next = { ...titles.value }
      for (const item of page.items ?? []) {
        if (item.name) next[item.name] = item.displayName?.trim() || item.name
      }
      titles.value = next
    }).catch(() => {
      pending = null
    })
    return pending
  }

  function title(id?: string | null, displayName?: string | null) {
    const given = displayName?.trim()
    if (given) return given
    if (!id) return ''
    return titles.value[id] || id
  }

  return { titles, ensure, title }
})
