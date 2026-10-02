import { defineStore } from 'pinia'
import { ref } from 'vue'

// TODO(P3-3): load the pending count from GET /approvals and refresh it after each decision.
export const useApprovalsStore = defineStore('approvals', () => {
  const pending = ref(0)
  return { pending }
})
