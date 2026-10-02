import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listApprovals, listSuspendedRuns } from '@/api/approvals'

export const useApprovalsStore = defineStore('approvals', () => {
  const pending = ref(0)

  async function refresh() {
    const [approvals, runs] = await Promise.all([listApprovals({ status: 'PENDING', size: 10 }), listSuspendedRuns({ size: 10 })])
    pending.value = (approvals.total ?? 0) + (runs.total ?? 0)
  }

  return { pending, refresh }
})
