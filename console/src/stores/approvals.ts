import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listApprovals, listSuspendedRuns } from '@/api/approvals'
import type { EnvFilter } from '@/stores/env'

export const useApprovalsStore = defineStore('approvals', () => {
  const pending = ref(0)

  async function refresh(env: EnvFilter = 'all') {
    const [approvals, runs] = await Promise.all([
      listApprovals({ status: 'PENDING', size: 10, env }),
      listSuspendedRuns({ size: 10, env }),
    ])
    pending.value = (approvals.total ?? 0) + (runs.total ?? 0)
  }

  return { pending, refresh }
})
