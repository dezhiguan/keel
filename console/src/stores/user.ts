import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getCurrentUser, type CurrentUser } from '@/api/me'

export const useUserStore = defineStore('user', () => {
  const user = ref<CurrentUser | null>(null)

  async function load() {
    user.value = await getCurrentUser()
  }

  return { user, load }
})
